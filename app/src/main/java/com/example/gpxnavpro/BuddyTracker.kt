package com.example.gpxnavpro

import android.content.Context
import android.os.SystemClock
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener

data class BuddyPeer(
    val uid: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    /** Metri percorsi sulla traccia, -1 se l'altra moto non sta navigando. */
    val progressMeters: Double,
    val routeKey: String,
    /** Istante locale (elapsedRealtime) dell'ultimo aggiornamento ricevuto. */
    val lastChangeElapsed: Long
)

/**
 * Condivisione della posizione tra più telefoni tramite Firebase Realtime Database.
 * Se manca google-services.json la funzione resta disattivata (isAvailable = false).
 *
 * Struttura dati: groups/<codice>/members/<uid> = { name, lat, lon, progress, route, t }
 */
class BuddyTracker(
    private val context: Context,
    private val onChanged: () -> Unit
) {
    val isAvailable: Boolean
        get() = FirebaseApp.getApps(context).isNotEmpty()

    var peers: List<BuddyPeer> = emptyList()
        private set
    var connected: Boolean = true
        private set
    var disconnectedSinceElapsed: Long = 0L
        private set
    var isRunning: Boolean = false
        private set

    private var sessionId = 0
    private var uid: String? = null
    private var displayName = "Moto"
    private var myRef: DatabaseReference? = null
    private var membersRef: DatabaseReference? = null
    private var connectedRef: DatabaseReference? = null
    private var membersListener: ValueEventListener? = null
    private var connectedListener: ValueEventListener? = null
    private var lastPublishElapsed = 0L
    private val lastSeen = HashMap<String, Pair<Long, Long>>() // uid -> (t remoto, istante locale)

    fun start(codeRaw: String, name: String, onError: (String) -> Unit) {
        if (!isAvailable) {
            onError("Funzione non configurata: manca google-services.json")
            return
        }
        val code = sanitizeGroupCode(codeRaw)
        if (code == null) {
            onError("Codice gruppo non valido: 6-32 caratteri tra lettere, numeri, - e _")
            return
        }
        stop(removeOwn = false)
        displayName = name.trim().ifBlank { "Moto" }.take(20)
        val session = ++sessionId
        isRunning = true

        runCatching {
            val auth = FirebaseAuth.getInstance()
            val current = auth.currentUser
            if (current != null) {
                attach(code, current.uid, session, onError)
            } else {
                auth.signInAnonymously()
                    .addOnSuccessListener { result ->
                        val newUid = result.user?.uid
                        if (newUid != null) attach(code, newUid, session, onError)
                        else onError("Accesso a Firebase non riuscito")
                    }
                    .addOnFailureListener { onError("Accesso a Firebase fallito: ${it.message}") }
            }
        }.onFailure { onError("Firebase non disponibile: ${it.message}") }
    }

    private fun attach(code: String, myUid: String, session: Int, onError: (String) -> Unit) {
        if (session != sessionId) return
        runCatching {
            uid = myUid
            val database = FirebaseDatabase.getInstance()
            val members = database.getReference("groups/$code/members")
            membersRef = members
            myRef = members.child(myUid)

            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    handleMembers(snapshot, myUid)
                }

                override fun onCancelled(error: DatabaseError) {
                    onError("Database: ${error.message}")
                }
            }
            membersListener = listener
            members.addValueEventListener(listener)

            val infoRef = database.getReference(".info/connected")
            connectedRef = infoRef
            val infoListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val isConnected = snapshot.getValue(Boolean::class.java) == true
                    if (!isConnected && connected) {
                        disconnectedSinceElapsed = SystemClock.elapsedRealtime()
                    }
                    connected = isConnected
                    onChanged()
                }

                override fun onCancelled(error: DatabaseError) = Unit
            }
            connectedListener = infoListener
            infoRef.addValueEventListener(infoListener)
        }.onFailure { onError("Firebase: ${it.message}") }
    }

    private fun handleMembers(snapshot: DataSnapshot, myUid: String) {
        val now = SystemClock.elapsedRealtime()
        val result = ArrayList<BuddyPeer>()
        val seenUids = HashSet<String>()
        for (child in snapshot.children) {
            val key = child.key ?: continue
            if (key == myUid) continue
            val lat = child.child("lat").getValue(Double::class.java) ?: continue
            val lon = child.child("lon").getValue(Double::class.java) ?: continue
            val remoteTime = child.child("t").getValue(Long::class.java) ?: 0L
            seenUids += key
            val previous = lastSeen[key]
            val changeElapsed = if (previous == null || previous.first != remoteTime) {
                lastSeen[key] = remoteTime to now
                now
            } else {
                previous.second
            }
            result += BuddyPeer(
                uid = key,
                name = child.child("name").getValue(String::class.java) ?: "Moto",
                latitude = lat,
                longitude = lon,
                progressMeters = child.child("progress").getValue(Double::class.java) ?: -1.0,
                routeKey = child.child("route").getValue(String::class.java) ?: "",
                lastChangeElapsed = changeElapsed
            )
        }
        lastSeen.keys.retainAll(seenUids)
        peers = result.sortedBy { it.name }
        onChanged()
    }

    /** Invia la posizione, al massimo una volta ogni intervalMs. */
    fun publishIfDue(
        latitude: Double,
        longitude: Double,
        progressMeters: Double,
        routeKey: String,
        intervalMs: Long = 5000L
    ) {
        val ref = myRef ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastPublishElapsed < intervalMs) return
        lastPublishElapsed = now
        runCatching {
            ref.setValue(
                mapOf<String, Any>(
                    "name" to displayName,
                    "lat" to latitude,
                    "lon" to longitude,
                    "progress" to progressMeters,
                    "route" to routeKey,
                    "t" to ServerValue.TIMESTAMP
                )
            )
        }
    }

    fun stop(removeOwn: Boolean) {
        sessionId++
        isRunning = false
        runCatching { membersListener?.let { membersRef?.removeEventListener(it) } }
        runCatching { connectedListener?.let { connectedRef?.removeEventListener(it) } }
        if (removeOwn) runCatching { myRef?.removeValue() }
        membersListener = null
        connectedListener = null
        membersRef = null
        connectedRef = null
        myRef = null
        uid = null
        peers = emptyList()
        lastSeen.clear()
        connected = true
        lastPublishElapsed = 0L
    }

    companion object {
        fun sanitizeGroupCode(raw: String): String? {
            val code = raw.trim()
            return if (Regex("^[A-Za-z0-9_-]{6,32}$").matches(code)) code else null
        }
    }
}
