package com.remixhub.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import com.remixhub.app.editor.EditorActivity

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        val newProjectButton =
            findViewById<Button>(R.id.btnNewProject)

        val myProjectsButton =
            findViewById<Button>(R.id.btnMyProjects)

        newProjectButton.setOnClickListener {
            val intent = Intent(
                this,
                EditorActivity::class.java
            )
            startActivity(intent)
        }

        myProjectsButton.setOnClickListener {
            Toast.makeText(
                this,
                "My Projects is coming soon!",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}