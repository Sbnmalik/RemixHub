package com.remixhub.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import com.remixhub.app.data.ProjectStore
import com.remixhub.app.editor.EditorActivity
import com.remixhub.app.projects.MyProjectsActivity

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val store = ProjectStore(this)

        findViewById<Button>(R.id.btnNewProject)
            .setOnClickListener {
                try {
                    val project = store.create()

                    val intent = Intent(
                        this,
                        EditorActivity::class.java
                    )

                    intent.putExtra(
                        EditorActivity.EXTRA_PROJECT_ID,
                        project.id
                    )

                    startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(
                        this,
                        "Unable to create project",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

        findViewById<Button>(R.id.btnMyProjects)
            .setOnClickListener {
                startActivity(
                    Intent(
                        this,
                        MyProjectsActivity::class.java
                    )
                )
            }
    }
}