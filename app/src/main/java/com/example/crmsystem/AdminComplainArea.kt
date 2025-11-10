package com.example.crmsystem

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date

// ---------- Allowed departments (used for normalization & safety) ----------
private val VALID_DEPARTMENTS = setOf(
    "TICKETING",
    "CATERING",
    "CLEANLINESS",
    "TRAIN_DELAY",
    "LOST_AND_FOUND",
    "MAINTENANCE",
    "SECURITY",
    "OTHER"
)

// ---------- Complaint model (includes department for filtering) ----------
data class ComplaintWithFeedback(
    val userId: String = "",
    val complaintText: String = "",
    val timestamp: Long = 0,
    val complaintId: String = "",
    val feedback: String? = null,
    val department: String = "OTHER"
)

// ---------- Screen ----------
class AdminComplainArea : AppCompatActivity() {

    private lateinit var database: FirebaseDatabase
    private lateinit var complaintsRecyclerView: RecyclerView
    private lateinit var adminComplaintsAdapter: AdminComplaintsAdapter
    private lateinit var backButton: Button

    // Keep references to detach listeners
    private var complaintsListener: ValueEventListener? = null
    private var complaintsQuery: Query? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_admin_complain_area)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom)
            insets
        }

        database = FirebaseDatabase.getInstance()

        complaintsRecyclerView = findViewById(R.id.adminComplaintsRecyclerView)
        backButton = findViewById(R.id.adminBackButton)

        adminComplaintsAdapter = AdminComplaintsAdapter { complaint, feedbackText ->
            submitFeedback(complaint, feedbackText)
        }
        complaintsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@AdminComplainArea)
            adapter = adminComplaintsAdapter
        }

        loadComplaintsForCurrentAdmin()   // 🔑 main logic

        backButton.setOnClickListener { finish() }
    }

    override fun onDestroy() {
        super.onDestroy()
        complaintsListener?.let { l -> complaintsQuery?.removeEventListener(l) }
    }

    private fun submitFeedback(complaint: ComplaintWithFeedback, feedbackText: String) {
        val text = feedbackText.trim()
        if (text.isEmpty()) {
            Toast.makeText(this, "Please enter feedback", Toast.LENGTH_SHORT).show()
            return
        }
        database.reference.child("complaints").child(complaint.complaintId)
            .child("feedback").setValue(text)
            .addOnSuccessListener {
                Toast.makeText(this, "Feedback submitted successfully", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Error submitting feedback: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    // ---------- Role/department-aware loading ----------
    private fun loadComplaintsForCurrentAdmin() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            Toast.makeText(this, "Please log in", Toast.LENGTH_SHORT).show()
            return
        }

        val userRef = database.reference.child("users").child(uid)
        userRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                val role = (snap.child("role").getValue(String::class.java) ?: "STAFF")
                    .trim().uppercase(Locale.ROOT)

                val deptRaw = snap.child("department").getValue(String::class.java) ?: "OTHER"
                val dept = deptRaw.trim().uppercase(Locale.ROOT).let { d ->
                    if (d in VALID_DEPARTMENTS) d else "OTHER"
                }

                // IMPORTANT:
                // - SUPER_ADMIN → can see ALL complaints
                // - ADMIN / STAFF (or anything else) → ONLY their department
                val isSuperAdmin = role == "SUPER_ADMIN"

                // Detach old listener if we’re reloading
                complaintsListener?.let { l -> complaintsQuery?.removeEventListener(l) }

                complaintsQuery = if (isSuperAdmin) {
                    database.reference.child("complaints")
                } else {
                    // Dept-scoped query (server-side) to avoid loading everything
                    database.reference.child("complaints")
                        .orderByChild("department")
                        .equalTo(dept)
                }

                complaintsListener = object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val items = mutableListOf<ComplaintWithFeedback>()
                        for (child in snapshot.children) {
                            val c = child.getValue(ComplaintWithFeedback::class.java)
                            if (c != null) items.add(c)
                        }
                        items.sortByDescending { it.timestamp }
                        adminComplaintsAdapter.submitList(items)
                    }

                    override fun onCancelled(error: DatabaseError) {
                        Toast.makeText(
                            this@AdminComplainArea,
                            "Error loading complaints: ${error.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                complaintsQuery!!.addValueEventListener(complaintsListener as ValueEventListener)
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(this@AdminComplainArea, "Error loading user: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    // (Optional) Old all-complaints loader retained for reference
    private fun loadAllComplaints() {
        database.reference.child("complaints")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val complaints = mutableListOf<ComplaintWithFeedback>()
                    for (data in snapshot.children) {
                        val complaint = data.getValue(ComplaintWithFeedback::class.java)
                        if (complaint != null) complaints.add(complaint)
                    }
                    complaints.sortByDescending { it.timestamp }
                    adminComplaintsAdapter.submitList(complaints)
                }

                override fun onCancelled(error: DatabaseError) {
                    Toast.makeText(this@AdminComplainArea, "Error loading complaints: ${error.message}", Toast.LENGTH_SHORT).show()
                }
            })
    }
}

// ---------- Adapter (unchanged UI) ----------
class AdminComplaintsAdapter(
    private val onFeedbackSubmit: (ComplaintWithFeedback, String) -> Unit
) : RecyclerView.Adapter<AdminComplaintsAdapter.AdminComplaintViewHolder>() {

    private var complaints: List<ComplaintWithFeedback> = emptyList()

    class AdminComplaintViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val complaintText: TextView = itemView.findViewById(R.id.adminComplaintText)
        val complaintDate: TextView = itemView.findViewById(R.id.adminComplaintDate)
        val feedbackEditText: EditText = itemView.findViewById(R.id.feedbackEditText)
        val submitFeedbackButton: Button = itemView.findViewById(R.id.submitFeedbackButton)
        val feedbackText: TextView = itemView.findViewById(R.id.feedbackText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AdminComplaintViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_admin_complaint, parent, false)
        return AdminComplaintViewHolder(view)
    }

    override fun onBindViewHolder(holder: AdminComplaintViewHolder, position: Int) {
        val complaint = complaints[position]
        holder.complaintText.text = complaint.complaintText
        holder.complaintDate.text = SimpleDateFormat(
            "dd MMM yyyy, HH:mm",
            Locale.getDefault()
        ).format(Date(complaint.timestamp))

        holder.feedbackText.text = complaint.feedback ?: "No feedback yet"
        holder.feedbackEditText.setText("")

        holder.submitFeedbackButton.setOnClickListener {
            val feedbackText = holder.feedbackEditText.text.toString().trim()
            onFeedbackSubmit(complaint, feedbackText)
        }
    }

    override fun getItemCount(): Int = complaints.size

    fun submitList(newComplaints: List<ComplaintWithFeedback>) {
        val diffCallback = object : DiffUtil.Callback() {
            override fun getOldListSize() = complaints.size
            override fun getNewListSize() = newComplaints.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                complaints[oldItemPosition].complaintId == newComplaints[newItemPosition].complaintId
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                complaints[oldItemPosition] == newComplaints[newItemPosition]
        }
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        complaints = newComplaints
        diffResult.dispatchUpdatesTo(this)
    }
}
