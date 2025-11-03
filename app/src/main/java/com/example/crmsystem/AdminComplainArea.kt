package com.example.crmsystem

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.*
import java.text.SimpleDateFormat
import java.util.*
import androidx.activity.enableEdgeToEdge
import androidx.recyclerview.widget.DiffUtil
import com.google.firebase.auth.FirebaseAuth

// ✅ Central list of valid departments (helps normalize/filter)
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

// Data class updated to include feedback + department (for filtering)
data class ComplaintWithFeedback(
    val userId: String = "",
    val complaintText: String = "",
    val timestamp: Long = 0,
    val complaintId: String = "",
    val feedback: String? = null,
    val department: String = "OTHER"
)

class AdminComplainArea : AppCompatActivity() {
    private lateinit var database: FirebaseDatabase
    private lateinit var complaintsRecyclerView: RecyclerView
    private lateinit var adminComplaintsAdapter: AdminComplaintsAdapter
    private lateinit var backButton: Button

    // keep references to detach listeners
    private var complaintsListener: ValueEventListener? = null
    private var complaintsQuery: Query? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_admin_complain_area)

        // Handle window insets
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Initialize Firebase
        database = FirebaseDatabase.getInstance()

        // Initialize UI elements
        complaintsRecyclerView = findViewById(R.id.adminComplaintsRecyclerView)
        backButton = findViewById(R.id.adminBackButton)

        // Set up RecyclerView with DiffUtil
        adminComplaintsAdapter = AdminComplaintsAdapter { complaint, feedbackText ->
            submitFeedback(complaint, feedbackText)
        }
        complaintsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@AdminComplainArea)
            adapter = adminComplaintsAdapter
        }

        // Load complaints based on current admin role/department
        loadComplaintsForCurrentAdmin()

        // Back button
        backButton.setOnClickListener { finish() }
    }

    override fun onDestroy() {
        super.onDestroy()
        // detach live listener if attached
        complaintsListener?.let { l ->
            complaintsQuery?.removeEventListener(l)
        }
    }

    private fun submitFeedback(complaint: ComplaintWithFeedback, feedbackText: String) {
        if (feedbackText.isEmpty()) {
            Toast.makeText(this, "Please enter feedback", Toast.LENGTH_SHORT).show()
            return
        }

        database.reference.child("complaints").child(complaint.complaintId)
            .child("feedback").setValue(feedbackText)
            .addOnSuccessListener {
                Toast.makeText(this, "Feedback submitted successfully", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Error submitting feedback: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    // role/department-aware loading
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

                // Normalize department to a known value
                val deptRaw = snap.child("department").getValue(String::class.java) ?: "OTHER"
                val dept = deptRaw.trim().uppercase(Locale.ROOT).let { d ->
                    if (d in VALID_DEPARTMENTS) d else "OTHER"
                }

                // Clean up any previous listener
                complaintsListener?.let { l -> complaintsQuery?.removeEventListener(l) }

                // 🔁 Load ALL complaints, then filter in code if not ADMIN
                complaintsQuery = database.reference.child("complaints")

                complaintsListener = object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val all = mutableListOf<ComplaintWithFeedback>()
                        for (data in snapshot.children) {
                            val c = data.getValue(ComplaintWithFeedback::class.java)
                            if (c != null) all.add(c)
                        }

                        // Filter: if ADMIN → all; else → only same department (case-insensitive)
                        val visible = if (role == "ADMIN") {
                            all
                        } else {
                            all.filter { it.department.equals(dept, ignoreCase = true) }
                        }

                        val sorted = visible.sortedByDescending { it.timestamp }
                        adminComplaintsAdapter.submitList(sorted)
                    }

                    override fun onCancelled(error: DatabaseError) {
                        Toast.makeText(this@AdminComplainArea, "Error loading complaints: ${error.message}", Toast.LENGTH_SHORT).show()
                    }
                }

                complaintsQuery!!.addValueEventListener(complaintsListener as ValueEventListener)
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(this@AdminComplainArea, "Error loading user: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    // (Optional) Old method kept for reference
    private fun loadAllComplaints() {
        database.reference.child("complaints")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val complaints = mutableListOf<ComplaintWithFeedback>()
                    for (data in snapshot.children) {
                        val complaint = data.getValue(ComplaintWithFeedback::class.java)
                        complaint?.let { complaints.add(it) }
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

        // Display existing feedback if any
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
