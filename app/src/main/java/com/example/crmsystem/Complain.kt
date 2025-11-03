package com.example.crmsystem

import android.graphics.Color
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RoundRectShape
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.google.genai.Client                           // ✅ new SDK
import com.google.genai.types.GenerateContentResponse   // ✅ new response type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

data class Complaint(
    val userId: String = "",
    val complaintText: String = "",
    val timestamp: Long = 0,
    val complaintId: String = "",
    val feedback: String? = null,
    val department: String = "OTHER"
)

class Complain : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var database: FirebaseDatabase

    private lateinit var complaintEditText: EditText
    private lateinit var submitButton: Button
    private lateinit var backButton: Button
    private lateinit var complaintsRecyclerView: RecyclerView
    private lateinit var complaintsAdapter: ComplaintsAdapter
    private lateinit var headerText: TextView

    private var isProcessing = false
    private lateinit var genAI: Client   // ✅

    private val validDepartments = setOf(
        "TICKETING",
        "CATERING",
        "CLEANLINESS",
        "TRAIN_DELAY",
        "LOST_AND_FOUND",
        "MAINTENANCE",
        "SECURITY",
        "OTHER"
    )


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_complain)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Firebase
        auth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance()

        // GenAI client (don’t hardcode keys)
        genAI = Client.builder()
            .apiKey("AIzaSyCbA8fhVTedRX0xwVsVJmIxXyDxrZ2kc6c")
            .build()

        // UI
        complaintEditText = findViewById(R.id.complaintEditText)
        submitButton = findViewById(R.id.submitButton)
        backButton = findViewById(R.id.backButton)
        complaintsRecyclerView = findViewById(R.id.complaintsRecyclerView)
        headerText = findViewById(R.id.complaintsHeader)

        complaintsAdapter = ComplaintsAdapter()
        complaintsRecyclerView.layoutManager = LinearLayoutManager(this)
        complaintsRecyclerView.adapter = complaintsAdapter

        loadPreviousComplaints()

        submitButton.setOnClickListener { submitComplaint() }
        backButton.setOnClickListener { finish() }
    }

    private fun submitComplaint() {
        if (isProcessing) return
        val text = complaintEditText.text.toString().trim()
        val user = auth.currentUser

        when {
            user == null -> Toast.makeText(this, "Please log in", Toast.LENGTH_SHORT).show()
            text.isEmpty() -> Toast.makeText(this, "Enter a complaint", Toast.LENGTH_SHORT).show()
            else -> processWithGenAI(text)
        }
    }

    private fun processWithGenAI(text: String) {
        lifecycleScope.launch {
            updateSubmitButton(true)
            try {
                val (department, rephrased) = classifyAndRephrase(text)
                saveComplaint(rephrased, department)
            } catch (e: Exception) {
                Log.e("COMPLAIN", "GenAI failed", e)
                saveComplaint(text, "OTHER")
                Toast.makeText(this@Complain, "AI failed, saved as OTHER", Toast.LENGTH_LONG).show()
            } finally {
                updateSubmitButton(false)
            }
        }
    }

    /** New SDK call */
    private suspend fun classifyAndRephrase(text: String): Pair<String, String> =
        withContext(Dispatchers.IO) {
            val prompt = """
                Classify this railway complaint into EXACTLY ONE department:
                TICKETING,CATERING,CLEANLINESS,PAYMENT, LOST_AND_FOUND,MAINTENANCE,
                SECURITY,OTHER

                Then rephrase it professionally and concisely in english.

                Output format strictly:
                Department: <DEPT>
                Rephrased: <text>

                Complaint: $text
            """.trimIndent()

            val response: GenerateContentResponse =
                genAI.models.generateContent("gemini-2.5-flash", prompt, /* config = */ null)
            val output = response.text() ?: ""
            Log.d("GENAI_RAW", output)

            val department = Regex("""Department:\s*([A-Z_]+)""", RegexOption.IGNORE_CASE)
                .find(output)
                ?.groupValues?.getOrNull(1)
                ?.trim()
                ?.uppercase()
                ?.takeIf { it in validDepartments } ?: "OTHER"

            val rephrased = output
                .lineSequence()
                .firstOrNull { it.trimStart().startsWith("Rephrased:", ignoreCase = true) }
                ?.substringAfter("Rephrased:", "")
                ?.trim()
                ?.ifBlank { text } ?: text

            department to rephrased
        }

    private fun saveComplaint(text: String, department: String) {
        val user = auth.currentUser ?: return
        val complaint = Complaint(
            userId = user.uid,
            complaintText = text,
            timestamp = System.currentTimeMillis(),
            complaintId = UUID.randomUUID().toString(),
            department = department
        )

        database.reference.child("complaints").child(complaint.complaintId)
            .setValue(complaint)
            .addOnSuccessListener {
                Toast.makeText(this, "Submitted to $department", Toast.LENGTH_SHORT).show()
                complaintEditText.text.clear()
                loadPreviousComplaints()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun updateSubmitButton(loading: Boolean) {
        isProcessing = loading
        submitButton.isEnabled = !loading
        submitButton.text = if (loading) "Processing..." else "Submit Complaint"
        submitButton.setBackgroundColor(Color.parseColor(if (loading) "#AAAAAA" else "#1E88E5"))
    }

    private fun loadPreviousComplaints() {
        val user = auth.currentUser ?: run {
            complaintsAdapter.submitList(emptyList())
            headerText.text = "No complaints yet"
            return
        }

        database.reference.child("complaints")
            .orderByChild("userId")
            .equalTo(user.uid)
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val list = snapshot.children
                        .mapNotNull { it.getValue(Complaint::class.java) }
                        .sortedByDescending { it.timestamp }
                    complaintsAdapter.submitList(list)
                    headerText.text = if (list.isEmpty()) "No complaints yet" else "Previous Complaints"
                }

                override fun onCancelled(error: DatabaseError) {
                    Toast.makeText(this@Complain, "Load failed: ${error.message}", Toast.LENGTH_SHORT).show()
                }
            })
    }
}

class ComplaintsAdapter : RecyclerView.Adapter<ComplaintsAdapter.ViewHolder>() {
    private var list: List<Complaint> = emptyList()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val complaintText: TextView = view.findViewById(R.id.complaintText)
        val complaintDate: TextView = view.findViewById(R.id.complaintDate)
        val feedbackText: TextView = view.findViewById(R.id.feedbackText)
        val departmentText: TextView = view.findViewById(R.id.departmentText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_complaint, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val c = list[position]
        holder.complaintText.text = c.complaintText
        holder.complaintDate.text = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
            .format(Date(c.timestamp))

        if (c.feedback != null) {
            holder.feedbackText.visibility = View.VISIBLE
            holder.feedbackText.text = "Feedback: ${c.feedback}"
        } else {
            holder.feedbackText.visibility = View.GONE
        }

        val deptName = c.department.replace("_", " ")
        holder.departmentText.text = deptName

        val color = when (c.department) {
            "CUSTOMER_SUPPORT" -> "#1E90FF"
            "OPERATIONS_CONTROL" -> "#FF4500"
            "HOUSEKEEPING" -> "#32CD32"
            "RAILWAY_PROTECTION" -> "#DC143C"
            "STATION_MANAGEMENT" -> "#9932CC"
            else -> "#808080"
        }

        val bg = ShapeDrawable(RoundRectShape(FloatArray(8) { 20f }, null, null))
        bg.paint.color = Color.parseColor(color)
        holder.departmentText.background = bg
        holder.departmentText.setTextColor(Color.WHITE)
    }

    override fun getItemCount(): Int = list.size
    fun submitList(newList: List<Complaint>) { list = newList; notifyDataSetChanged() }
}
