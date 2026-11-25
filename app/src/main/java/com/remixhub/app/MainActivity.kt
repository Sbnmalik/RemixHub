
package com.remixhub.app

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.Toast

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        val newProjectButton =
            findViewById<Button>(R.id.btnNewProject)

        val myProjectsButton =
            findViewById<Button>(R.id.btnMyProjects)

        newProjectButton.setOnClickListener {
            Toast.makeText(
                this,
                "New project coming next!",
                Toast.LENGTH_SHORT
            ).show()
        }

        myProjectsButton.setOnClickListener {
            Toast.makeText(
                this,
                "My projects coming next!",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}