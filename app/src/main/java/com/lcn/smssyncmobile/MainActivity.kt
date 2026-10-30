package com.lcn.smssyncmobile

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.provider.Telephony
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.billingclient.api.*
import com.google.android.gms.auth.api.signin.*
import com.google.android.gms.common.api.ApiException
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.firebase.auth.*
import com.google.firebase.firestore.*
class MainActivity : AppCompatActivity() {
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var tvSubtitle: TextView
    private lateinit var tvSyncModeStatus: TextView
    private lateinit var statusDot: View
    private lateinit var btnResync: MaterialButton
    private lateinit var btnRefresh: MaterialButton
    private lateinit var rvSmsList: RecyclerView
    private lateinit var tvEmptyState: TextView
    private lateinit var smsAdapter: SmsAdapter
    private lateinit var billingClient: BillingClient
    private val premiumProductId = "app_premium_license"
    private var firestoreListener: ListenerRegistration? = null
    private val WEB_CLIENT_ID = "://googleusercontent.com"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        checkLicenseAndTrialStatus()
        setupBillingClient()
        findViewById<Button>(R.id.btnBuyPremiumLock)?.setOnClickListener { launchBillingFlow() }

        tvSubtitle = findViewById(R.id.tvSubtitle)
        tvSyncModeStatus = findViewById(R.id.tvSyncModeStatus)
        statusDot = findViewById(R.id.statusDot)
        btnResync = findViewById(R.id.btnResync)
        btnRefresh = findViewById(R.id.btnRefresh)
        rvSmsList = findViewById(R.id.rvSmsList)
        tvEmptyState = findViewById(R.id.tvEmptyState)

        smsAdapter = SmsAdapter { smsBody ->
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("SMS", smsBody))
            Toast.makeText(this, "Đã sao chép tin nhắn!", Toast.LENGTH_SHORT).show()
        }
        rvSmsList.layoutManager = LinearLayoutManager(this)
        rvSmsList.adapter = smsAdapter

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        googleSignInClient = GoogleSignIn.getClient(this, GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(WEB_CLIENT_ID).requestEmail().build())

        requestRequiredPermissions()
        findViewById<Button>(R.id.btnOpenSettings)?.setOnClickListener { showOneUiSettingsSheet() }
        btnResync.setOnClickListener { resyncLocalSmsToCloud(SyncPrefs.getSmsLimit(this)) }
        btnRefresh.setOnClickListener { manualRefreshFromCloud() }

        applySyncModeAndUpdateUI()
    }
    private fun checkLicenseAndTrialStatus() {
        val sharedPref = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        if (sharedPref.getBoolean("is_premium", false)) {
            findViewById<RelativeLayout>(R.id.layoutLockScreen)?.visibility = View.GONE
            return
        }
        var firstLaunch = sharedPref.getLong("first_launch_time", 0L)
        if (firstLaunch == 0L) {
            firstLaunch = System.currentTimeMillis()
            sharedPref.edit().putLong("first_launch_time", firstLaunch).apply()
        }
        if (System.currentTimeMillis() - firstLaunch > 5L * 24 * 60 * 60 * 1000) {
            findViewById<RelativeLayout>(R.id.layoutLockScreen)?.visibility = View.VISIBLE
        } else {
            findViewById<RelativeLayout>(R.id.layoutLockScreen)?.visibility = View.GONE
        }
    }

    private fun setupBillingClient() {
        billingClient = BillingClient.newBuilder(this).setListener { res, purchases ->
            if (res.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
                purchases.forEach { handlePurchase(it) }
            }
        }.enablePendingPurchases().build()

        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(res: BillingResult) {
                if (res.responseCode == BillingClient.BillingResponseCode.OK) queryPurchasesOnLaunch()
            }
            override fun onBillingServiceDisconnected() {}
        })
    }
    private fun launchBillingFlow() {
        val params = QueryProductDetailsParams.newBuilder().setProductList(listOf(
            QueryProductDetailsParams.Product.newBuilder().setProductId(premiumProductId).setProductType(BillingClient.ProductType.INAPP).build()
        )).build()

        billingClient.queryProductDetailsAsync(params) { res, list ->
            if (res.responseCode == BillingClient.BillingResponseCode.OK && list.isNotEmpty()) {
                billingClient.launchBillingFlow(this, BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(list).build())).build())
            } else {
                runOnUiThread { Toast.makeText(this, "Lỗi kết nối CH Play", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
            if (!purchase.isAcknowledged) {
                billingClient.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()) { res ->
                    if (res.responseCode == BillingClient.BillingResponseCode.OK) savePremiumStatus(true)
                }
            } else { savePremiumStatus(true) }
        }
    }

    private fun queryPurchasesOnLaunch() {
        billingClient.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()) { res, list ->
            if (res.responseCode == BillingClient.BillingResponseCode.OK) {
                savePremiumStatus(list.any { it.products.contains(premiumProductId) && it.purchaseState == Purchase.PurchaseState.PURCHASED })
            }
        }
    }

    private fun savePremiumStatus(isPremium: Boolean) {
        getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).edit().putBoolean("is_premium", isPremium).apply()
        runOnUiThread {
            if (isPremium) {
                findViewById<RelativeLayout>(R.id.layoutLockScreen)?.visibility = View.GONE
                Toast.makeText(this, "Kích hoạt bản quyền thành công!", Toast.LENGTH_LONG).show()
            }
        }
    }
    private fun showOneUiSettingsSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_oneui_settings, null)
        dialog.setContentView(view)

        val tvEmail = view.findViewById<TextView>(R.id.tvSheetAccountEmail)
        val tvUid = view.findViewById<TextView>(R.id.tvSheetAccountUid)
        val btnAuth = view.findViewById<MaterialButton>(R.id.btnSheetGoogleAuth)
        val switchSync = view.findViewById<SwitchMaterial>(R.id.switchAutoSync)
        val spinnerLimit = view.findViewById<Spinner>(R.id.spinnerSmsLimit)
        
        view.findViewById<Button>(R.id.btnMenuBuyPremium)?.setOnClickListener { launchBillingFlow() }

        val user = auth.currentUser
        if (user != null) {
            tvEmail.text = user.email
            tvUid.text = "UID: ${user.uid.take(10)}..."
            btnAuth.text = "Đăng xuất"
            btnAuth.setBackgroundColor(Color.parseColor("#FF3B30"))
        } else {
            tvEmail.text = "Chưa đăng nhập"
            tvUid.text = "Đăng nhập Gmail để đồng bộ"
            btnAuth.text = "Đăng nhập với Google"
            btnAuth.setBackgroundColor(Color.parseColor("#0381FE"))
        }

        btnAuth.setOnClickListener {
            dialog.dismiss()
            if (auth.currentUser != null) {
                auth.signOut()
                googleSignInClient.signOut().addOnCompleteListener {
                    firestoreListener?.remove()
                    smsAdapter.submitList(emptyList())
                    applySyncModeAndUpdateUI()
                }
            } else { signInLauncher.launch(googleSignInClient.signInIntent) }
        }

        switchSync.isChecked = SyncPrefs.isAutoSync(this)
        switchSync.setOnCheckedChangeListener { _, isChecked ->
            SyncPrefs.setAutoSync(this, isChecked)
            applySyncModeAndUpdateUI()
        }

        val limits = intArrayOf(10, 20, 30, 50)
        spinnerLimit.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, arrayOf("10 tin", "20 tin", "30 tin", "50 tin"))
        spinnerLimit.setSelection(limits.indexOf(SyncPrefs.getSmsLimit(this)).takeIf { it >= 0 } ?: 3)
        spinnerLimit.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>, v: View?, pos: Int, id: Long) {
                SyncPrefs.setSmsLimit(this@MainActivity, limits[pos])
                applySyncModeAndUpdateUI()
            }
            override fun onNothingSelected(p: AdapterView<*>) {}
        }
        dialog.show()
    }
    private fun applySyncModeAndUpdateUI() {
        val user = auth.currentUser
        if (user == null) {
            tvSubtitle.text = "Chưa kết nối tài khoản"
            tvSyncModeStatus.text = "Trạng thái: Ngắt kết nối"
            statusDot.setBackgroundResource(android.R.drawable.presence_offline)
            tvEmptyState.visibility = View.VISIBLE
            return
        }
        tvSubtitle.text = "Tài khoản: ${user.email}"
        if (SyncPrefs.isAutoSync(this)) {
            tvSyncModeStatus.text = "Chế độ: Tự động (Real-time)"
            statusDot.setBackgroundResource(android.R.drawable.presence_online)
            startRealtimeListener(user.uid)
        } else {
            tvSyncModeStatus.text = "Chế độ: Thủ công"
            statusDot.setBackgroundResource(android.R.drawable.presence_away)
            firestoreListener?.remove()
            firestoreListener = null
            loadOnceFromCacheOrServer(user.uid, Source.DEFAULT)
        }
    }

    private fun startRealtimeListener(uid: String) {
        firestoreListener?.remove()
        firestoreListener = db.collection("users").document(uid).collection("sms_list")
            .orderBy("timestamp", Query.Direction.DESCENDING).limit(SyncPrefs.getSmsLimit(this).toLong())
            .addSnapshotListener { snaps, err -> if (err == null && snaps != null) renderSmsList(snaps.documents) }
    }

    private fun loadOnceFromCacheOrServer(uid: String, source: Source) {
        db.collection("users").document(uid).collection("sms_list")
            .orderBy("timestamp", Query.Direction.DESCENDING).limit(SyncPrefs.getSmsLimit(this).toLong())
            .get(source).addOnSuccessListener { renderSmsList(it.documents) }
    }

    private fun renderSmsList(docs: List<DocumentSnapshot>) {
        smsAdapter.submitList(docs)
        tvEmptyState.visibility = if (docs.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun resyncLocalSmsToCloud(maxCount: Int) {
        val user = auth.currentUser ?: return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return
        btnResync.isEnabled = false
        val cursor = contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE), null, null, "${Telephony.Sms.DATE} DESC LIMIT $maxCount")
        if (cursor == null || !cursor.moveToFirst()) { cursor?.close(); btnResync.isEnabled = true; return }
        val batch = db.batch()
        val colRef = db.collection("users").document(user.uid).collection("sms_list")
        do {
            val time = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.DATE))
            val sender = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)) ?: "Unknown"
            batch.set(colRef.document("${time}_${sender.hashCode()}"), hashMapOf("sender" to sender, "body" to cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)) ?: "", "timestamp" to time, "deviceModel" to "${Build.MANUFACTURER} ${Build.MODEL}", "syncedAt" to System.currentTimeMillis()))
        } while (cursor.moveToNext())
        cursor.close()
        batch.commit().addOnCompleteListener { btnResync.isEnabled = true; if (it.isSuccessful && !SyncPrefs.isAutoSync(this)) manualRefreshFromCloud() }
    }

    private fun manualRefreshFromCloud() {
        val user = auth.currentUser ?: return
        btnRefresh.isEnabled = false
        db.collection("users").document(user.uid).collection("sms_list")
            .orderBy("timestamp", Query.Direction.DESCENDING).limit(SyncPrefs.getSmsLimit(this).toLong())
            .get(Source.SERVER).addOnCompleteListener { btnRefresh.isEnabled = true; if (it.isSuccessful && it.result != null) renderSmsList(it.result.documents) }
    }

    private val signInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(res.data).getResult(ApiException::class.java)!!
            auth.signInWithCredential(GoogleAuthProvider.getCredential(account.idToken, null)).addOnCompleteListener(this) { if (it.isSuccessful) applySyncModeAndUpdateUI() }
        } catch (e: Exception) {}
    }

    private fun requestRequiredPermissions() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS), 101)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply { data = Uri.parse("package:$packageName") })
            }
        }
    }

    override fun onDestroy() { super.onDestroy(); firestoreListener?.remove() }
}
