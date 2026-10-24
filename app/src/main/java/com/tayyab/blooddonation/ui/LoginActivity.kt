package com.tayyab.blooddonation.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tayyab.blooddonation.R
import com.tayyab.blooddonation.db.DbHelper
import com.tayyab.blooddonation.util.Session

class LoginActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Session.load(this)
        if (Session.isLoggedIn) {
            goToMain()
            return
        }
        setContentView(R.layout.activity_login)
        title = "Sign In"
        val db = DbHelper(this)

        findViewById<Button>(R.id.btnLogin).setOnClickListener {
            val username = findViewById<EditText>(R.id.etUsername).text.toString().trim()
            val password = findViewById<EditText>(R.id.etPassword).text.toString()
            if (username.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Enter username and password", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val user = db.getUser(username)
            if (user == null || user.passwordHash != Session.hashPassword(username, password)) {
                Toast.makeText(this, "Wrong username or password", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Session.save(this, user.username, user.role, user.donorId)
            Toast.makeText(this, "Welcome, ${user.username}", Toast.LENGTH_SHORT).show()
            goToMain()
        }

        findViewById<Button>(R.id.btnRegister).setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
