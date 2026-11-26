package com.remixhub.app.projects

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.remixhub.app.R
import com.remixhub.app.data.ProjectStore
import com.remixhub.app.editor.EditorActivity
import java.text.DateFormat
import java.util.Date

class MyProjectsActivity : Activity() {

    private lateinit var store: ProjectStore
    private lateinit var container: LinearLayout
    private lateinit var emptyText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_my_projects)

        store = ProjectStore(this)

        container = findViewById(R.id.projectContainer)
        emptyText = findViewById(R.id.textEmpty)

        findViewById<TextView>(R.id.textBack)
            .setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        displayProjects()
    }

    private fun displayProjects() {
        container.removeAllViews()

        val projects = store.getAll()

        emptyText.visibility =
            if (projects.isEmpty()) View.VISIBLE else View.GONE

        projects.forEach { project ->
            val history = project.history.joinToString("\n") {
                "• $it"
            }

            val updated = DateFormat
                .getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(Date(project.updatedAt))

            val button = Button(this).apply {
                text = project.name
                isAllCaps = false
                setTextColor(getColor(R.color.white))
                backgroundTintList = android.content.res.ColorStateList
                    .valueOf(getColor(R.color.black))

                setOnClickListener {
                    val intent = Intent(
                        this@MyProjectsActivity,
                        EditorActivity::class.java
                    )
                    intent.putExtra(
                        EditorActivity.EXTRA_PROJECT_ID,
                        project.id
                    )
                    startActivity(intent)
                }
            }

            val details = TextView(this).apply {
                text = "Updated: $updated\n\nRemix history:\n$history"
                setTextColor(getColor(R.color.gray))
                textSize = 14f
                setPadding(8, 8, 8, 32)
            }

            container.addView(button)
            container.addView(details)
        }
    }
}