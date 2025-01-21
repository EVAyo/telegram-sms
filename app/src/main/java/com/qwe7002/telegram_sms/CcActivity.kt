@file:Suppress("DEPRECATION")

package com.qwe7002.telegram_sms

import android.Manifest
import android.annotation.SuppressLint
import android.app.ProgressDialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputLayout
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.qwe7002.telegram_sms.config.proxy
import com.qwe7002.telegram_sms.data_structure.CcConfig
import com.qwe7002.telegram_sms.data_structure.CcSendService
import com.qwe7002.telegram_sms.data_structure.HAR
import com.qwe7002.telegram_sms.static_class.AES
import com.qwe7002.telegram_sms.static_class.Logs
import com.qwe7002.telegram_sms.static_class.Network
import com.qwe7002.telegram_sms.static_class.Template
import com.qwe7002.telegram_sms.value.constValue
import io.paperdb.Paper
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.IOException

@Suppress("NAME_SHADOWING")
class CcActivity : AppCompatActivity() {
    private lateinit var listAdapter: ArrayAdapter<CcSendService>
    private lateinit var serviceList: ArrayList<CcSendService>
    private val url = "https://api.telegram-sms.com/cc-config".toHttpUrlOrNull()!!
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cc)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        val inflater = this.layoutInflater
        val fab = findViewById<FloatingActionButton>(R.id.cc_fab)
        val ccList = findViewById<ListView>(R.id.cc_list)

        val serviceListJson =
            Paper.book("carbon_copy").read("CC_service_list", "[]").toString()
        val gson = Gson()
        val type = object : TypeToken<ArrayList<CcSendService>>() {}.type
        serviceList = gson.fromJson(serviceListJson, type)
        listAdapter =
            object :
                ArrayAdapter<CcSendService>(this, R.layout.list_item_with_subtitle, serviceList) {
                @SuppressLint("SetTextI18n")
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val view = convertView ?: layoutInflater.inflate(
                        R.layout.list_item_with_subtitle,
                        parent,
                        false
                    )
                    val item = getItem(position)!!

                    val title = view.findViewById<TextView>(R.id.title)
                    title.text =
                        item.name + item.enabled.let { if (it) " (Enabled)" else " (Disabled)" }
                    val subtitle = view.findViewById<TextView>(R.id.subtitle)
                    val log = item.har.log
                    if (log.entries.isNotEmpty()) {
                        subtitle.text = log.entries[0].request.url
                    } else {
                        subtitle.text = "No entries available"
                    }

                    return view
                }
            }
        ccList.adapter = listAdapter
        ccList.onItemClickListener =
            AdapterView.OnItemClickListener { _: AdapterView<*>?, _: View?, position: Int, _: Long ->
                val dialog = inflater.inflate(R.layout.set_cc_layout, null)

                val webhook =
                    dialog.findViewById<EditText>(R.id.har_editview)
                webhook.setText(gson.toJson(serviceList[position].har))
                webhook.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(
                        s: CharSequence?,
                        start: Int,
                        count: Int,
                        after: Int
                    ) {
                    }

                    override fun onTextChanged(
                        s: CharSequence?,
                        start: Int,
                        before: Int,
                        count: Int
                    ) {
                    }

                    override fun afterTextChanged(s: Editable?) {
                        val text = s.toString()
                        if (!isValidHarJson(text)) {
                            webhook.error = "Invalid JSON structure"
                        }
                    }
                })
                val switch =
                    dialog.findViewById<SwitchMaterial>(R.id.cc_enable_switch)
                switch.isChecked = serviceList[position].enabled
                val name = dialog.findViewById<EditText>(R.id.cc_service_name)
                name.setText(serviceList[position].name)
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.edit_cc_service))
                    .setView(dialog)
                    .setPositiveButton(R.string.ok_button) { _: DialogInterface?, _: Int ->
                        CcSendService(
                            name = name.text.toString(),
                            enabled = switch.isChecked,
                            har = gson.fromJson(webhook.text.toString(), HAR::class.java)
                        ).also { serviceList[position] = it }
                        saveAndFlush(serviceList, listAdapter)
                    }
                    .setNeutralButton(R.string.cancel_button, null)
                    .setNegativeButton(
                        R.string.delete_button,
                        ((DialogInterface.OnClickListener { _: DialogInterface?, _: Int ->
                            serviceList.removeAt(position)
                            saveAndFlush(serviceList, listAdapter)
                        }))
                    )
                    .show()
            }

        fab.setOnClickListener {
            val dialog = inflater.inflate(R.layout.set_cc_layout, null)
            val webhook = dialog.findViewById<EditText>(R.id.har_editview)
            webhook.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) {
                }

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

                override fun afterTextChanged(s: Editable?) {
                    val text = s.toString()
                    if (!isValidHarJson(text)) {
                        webhook.error = "Invalid JSON structure"
                    }
                }
            })
            val name = dialog.findViewById<EditText>(R.id.cc_service_name)
            AlertDialog.Builder(this).setTitle(getString(R.string.add_cc_service))
                .setView(dialog)
                .setPositiveButton(R.string.ok_button) { _: DialogInterface?, _: Int ->
                    CcSendService(
                        name = name.text.toString(),
                        enabled = dialog.findViewById<SwitchMaterial>(R.id.cc_enable_switch).isChecked,
                        har = gson.fromJson(webhook.text.toString(), HAR::class.java)
                    ).also { serviceList.add(it) }
                    saveAndFlush(serviceList, listAdapter)
                }
                .setNeutralButton(R.string.cancel_button, null)
                .show()
        }
    }

    private fun isValidHarJson(json: String): Boolean {
        return try {
            val gson = Gson()
            gson.fromJson(json, HAR::class.java)
            true
        } catch (ex: Exception) {
            ex.printStackTrace()
            false
        }
    }

    private fun saveAndFlush(
        serviceList: ArrayList<CcSendService>,
        listAdapter: ArrayAdapter<CcSendService>
    ) {
        Log.d("save_and_flush", serviceList.toString())
        Paper.book("carbon_copy").write("CC_service_list", Gson().toJson(serviceList))
        listAdapter.notifyDataSetChanged()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.cc_menu, menu)
        return true
    }

    @SuppressLint("NonConstantResourceId", "SetTextI18n")
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.scan_menu_item -> {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 0)
                return true
            }

            R.id.send_test_menu_item -> {
                if (serviceList.isEmpty()) {
                    Snackbar.make(
                        findViewById(R.id.send_test_menu_item),
                        "No service available.",
                        Snackbar.LENGTH_SHORT
                    ).show()
                    return true
                }
                CcSendJob.startJob(
                    applicationContext,
                    -1,
                    getString(R.string.app_name),
                    Template.render(
                        applicationContext,
                        "TPL_system_message",
                        mapOf("Message" to "This is a test message.")
                    )
                )
                Snackbar.make(
                    findViewById(R.id.send_test_menu_item),
                    "Test message sent.",
                    Snackbar.LENGTH_SHORT
                ).show()
                return true
            }

            R.id.receive_config_menu_item -> {
                getConfig()
                return true
            }

            R.id.cc_config_menu_item -> {
                val inflater = this.layoutInflater
                val dialog = inflater.inflate(R.layout.set_cc_config_layout, null)
                val receiverSMSSwitch = dialog.findViewById<SwitchMaterial>(R.id.cc_sms_switch)
                val receiverCallSwitch = dialog.findViewById<SwitchMaterial>(R.id.cc_call_switch)
                val receiverNotificationSwitch =
                    dialog.findViewById<SwitchMaterial>(R.id.cc_notify_switch)
                val receiverBatterySwitch =
                    dialog.findViewById<SwitchMaterial>(R.id.cc_battery_switch)
                val ccConfig = Paper.book("carbon_copy").read("cc_config", "{}").toString()
                val gson = Gson()
                val type = object : TypeToken<CcConfig>() {}.type
                val config: CcConfig = gson.fromJson(ccConfig, type)

                receiverSMSSwitch.isChecked = config.receiveSMS
                receiverCallSwitch.isChecked = config.missedCall
                receiverNotificationSwitch.isChecked = config.receiveNotification
                receiverBatterySwitch.isChecked = config.battery
                AlertDialog.Builder(this)
                    .setTitle("Please select the service that needs to be copied")
                    .setView(dialog)
                    .setPositiveButton(R.string.ok_button) { _: DialogInterface?, _: Int ->
                        val ccConfig = CcConfig(
                            receiverSMSSwitch.isChecked,
                            receiverCallSwitch.isChecked,
                            receiverNotificationSwitch.isChecked,
                            receiverBatterySwitch.isChecked
                        )
                        Paper.book("carbon_copy").write("cc_config", Gson().toJson(ccConfig))
                    }
                    .setNeutralButton(R.string.cancel_button, null)
                    .show()
                return true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    @Suppress("DEPRECATION")
    private fun getConfig() {
        showGetDialog(this, getString(R.string.please_enter_your_info)) { id, password ->
            val progressDialog = ProgressDialog(this)
            progressDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER)
            progressDialog.setTitle(getString(R.string.getting_configuration))
            progressDialog.setMessage(getString(R.string.connect_wait_message))
            progressDialog.isIndeterminate = false
            progressDialog.setCancelable(false)
            progressDialog.show()
            Thread {
                val sharedPreferences =
                    applicationContext.getSharedPreferences("data", MODE_PRIVATE)
                val okhttpObject = Network.getOkhttpObj(
                    sharedPreferences.getBoolean("doh_switch", true),
                    Paper.book("system_config").read("proxy_config", proxy())
                )
                val httpUrlBuilder: HttpUrl.Builder = url.newBuilder()
                httpUrlBuilder.addQueryParameter("key", id)
                val httpUrl = httpUrlBuilder.build()
                val requestObj = Request.Builder().url(httpUrl).method("GET", null)
                val call = okhttpObject.newCall(requestObj.build())
                try {
                    val response = call.execute()
                    if (response.code == 200) {
                        val responseBody = response.body.string()
                        try {
                            val decryptConfig =
                                AES.decrypt(responseBody, AES.getKeyFromString(password))
                            val gson = Gson()
                            val config = gson.fromJson(decryptConfig, CcSendService::class.java)
                            serviceList.add(config)
                            saveAndFlush(serviceList, listAdapter)
                        } catch (e: Exception) {
                            Logs.writeLog(
                                applicationContext,
                                "An error occurred while resending: " + e.message
                            )
                            runOnUiThread {
                                AlertDialog.Builder(this)
                                    .setTitle(R.string.error_title)
                                    .setMessage(getString(R.string.an_error_occurred_while_decrypting_the_configuration))
                                    .setPositiveButton("OK") { _, _ -> getConfig() }
                                    .show()

                            }
                            e.printStackTrace()
                        }
                    } else {
                        runOnUiThread {
                            AlertDialog.Builder(this)
                                .setTitle(R.string.error_title)
                                .setMessage(getString(R.string.an_error_occurred_while_getting_the_configuration) + response.code)
                                .setPositiveButton("OK") { _, _ -> }
                                .show()
                        }
                    }
                } catch (e: IOException) {
                    Logs.writeLog(
                        applicationContext,
                        "An error occurred while resending: " + e.message
                    )
                    e.printStackTrace()
                } finally {
                    progressDialog.dismiss()
                }
            }.start()
        }
    }

    @Suppress("SameParameterValue")
    private fun showGetDialog(context: Context, title: String, callback: (String, String) -> Unit) {
        val builder = AlertDialog.Builder(context)
        builder.setTitle(title)
        val dialogView = layoutInflater.inflate(R.layout.set_config_layout, null)
        builder.setView(dialogView)
        val idInput = dialogView.findViewById<EditText>(R.id.config_id_editview)
        val passwordInput = dialogView.findViewById<EditText>(R.id.config_password_editview)
        builder.setPositiveButton("OK", null)
        builder.setNegativeButton("Cancel") { dialog, _ ->
            dialog.cancel()
        }

        val dialog = builder.create()
        dialog.setOnShowListener {
            val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            button.setOnClickListener {
                val idView = dialogView.findViewById<TextInputLayout>(R.id.config_id_layout)
                val passwordView =
                    dialogView.findViewById<TextInputLayout>(R.id.config_password_layout)
                val id = idInput.text.toString()
                val password = passwordInput.text.toString()
                if (id.isEmpty()) {
                    idView.error = getString(R.string.error_id_cannot_be_empty)
                } else if (password.isEmpty()) {
                    passwordView.error = getString(R.string.error_password_cannot_be_empty)
                } else if (id.length != 9) {
                    idView.error = getString(R.string.error_id_must_be_9_characters)
                } else {
                    callback(id, password)
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            0 -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    Log.d("CcActivity", "onRequestPermissionsResult: No camera permissions.")
                    Snackbar.make(
                        findViewById(R.id.bot_token_editview),
                        R.string.no_camera_permission,
                        Snackbar.LENGTH_LONG
                    ).show()
                    return
                }
                val intent = Intent(applicationContext, ScannerActivity::class.java)
                @Suppress("DEPRECATION")
                startActivityForResult(intent, 1)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Log.d("onActivityResult", "onActivityResult: $resultCode")
        if (requestCode == 1) {
            if (resultCode == constValue.RESULT_CONFIG_JSON) {
                val gson = Gson()
                val jsonConfig = gson.fromJson(
                    data!!.getStringExtra("config_json"),
                    CcSendService::class.java
                )
                Log.d("onActivityResult", "onActivityResult: $jsonConfig")
                serviceList.add(jsonConfig)
                saveAndFlush(serviceList, listAdapter)
            }
        }
    }
}
