package com.lcn.smssyncmobile

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.provider.Telephony
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.RelativeLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.billingclient.api.*
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import options = com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source

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
    
    // Khởi tạo các biến quản lý mua bản quyền Google Play Billing
    private lateinit var billingClient: BillingClient
    private val premiumProductId = "smssyncmobile1"

    private var firestoreListener: ListenerRegistration? = null
    private val WEB_CLIENT_ID = "://googleusercontent.com"
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Khởi động luồng kiểm tra bản quyền 5 ngày và cổng Billing CH Play
        checkLicenseAndTrialStatus()
        setupBillingClient()
        findViewById<Button>(R.id.btnBuyPremiumLock)?.setOnClickListener { launchBillingFlow() }

        // Khởi tạo các nút bấm giao diện và Adapter hiển thị tin nhắn SMS
        tvSubtitle = findViewById(R.id.tvSubtitle)
        tvSyncModeStatus = findViewById(R.id.tvSyncModeStatus)
        statusDot = findViewById(R.id.statusDot)
        btnResync = findViewById(R.id.btnResync)
        btnRefresh = findViewById(R.id.btnRefresh)
        rvSmsList = findViewById(R.id.rvSmsList)
        tvEmptyState = findViewById(R.id.tvEmptyState)
        val btnOpenSettings = findViewById<Button>(R.id.btnOpenSettings)

        smsAdapter = SmsAdapter { smsBody ->
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("SMS", smsBody))
            Toast.makeText(this, "Đã sao chép nội dung tin nhắn!", Toast.LENGTH_SHORT).show()
        }
        rvSmsList.layoutManager = LinearLayoutManager(this)
        rvSmsList.adapter = smsAdapter

        // Cấu hình dịch vụ kết nối Firebase Auth và Firestore
        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(WEB_CLIENT_ID)
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)

        requestRequiredPermissions()

        btnOpenSettings.setOnClickListener { showOneUiSettingsSheet() }
        btnResync.setOnClickListener { 
            val limit = SyncPrefs.getSmsLimit(this)
            resyncLocalSmsToCloud(maxCount = limit)
        }
        btnRefresh.setOnClickListener { manualRefreshFromCloud() }

        applySyncModeAndUpdateUI()
    }
    // ==========================================
    // KHỐI QUẢN LÝ BẢN QUYỀN (5 NGÀY DÙNG THỬ & KHÓA)
    // ==========================================
    private fun checkLicenseAndTrialStatus() {
        val sharedPref = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val isPurchased = sharedPref.getBoolean("is_premium", false)
        
        if (isPurchased) {
            findViewById<RelativeLayout>(R.id.layoutLockScreen)?.visibility = View.GONE
            return
        }

        var firstLaunchTime = sharedPref.getLong("first_launch_time", 0L)
        if (firstLaunchTime == 0L) {
            firstLaunchTime = System.currentTimeMillis()
            sharedPref.edit().putLong("first_launch_time", firstLaunchTime).apply()
        }

        val fiveDaysInMs = 5L * 24 * 60 * 60 * 1000
        val currentTime = System.currentTimeMillis()

        if (currentTime - firstLaunchTime > fiveDaysInMs) {
            findViewById<RelativeLayout>(R.id.layoutLockScreen)?.visibility = View.VISIBLE
        } else {
            findViewById<RelativeLayout>(R.id.layoutLockScreen)?.visibility = View.GONE
        }
    }

    private fun setupBillingClient() {
        billingClient = BillingClient.newBuilder(this)
            .setListener { billingResult, purchases ->
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
                    for (purchase in purchases) {
                        handlePurchase(purchase)
                    }
                }
            }
            .enablePendingPurchases()
            .build()

        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryPurchasesOnLaunch()
                }
            }
            override fun onBillingServiceDisconnected() {}
        })
    }

    private fun launchBillingFlow() {
        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(premiumProductId)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        )
        val params = QueryProductDetailsParams.newBuilder().setProductList(productList).build()

        billingClient.queryProductDetailsAsync(params) { billingResult, productDetailsList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && productDetailsList.isNotEmpty()) {
                val productDetails = productDetailsList
                val billingFlowParams = BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(listOf(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                            .setProductDetails(productDetails)
                            .build()
                    ))
                    .build()
                billingClient.launchBillingFlow(this, billingFlowParams)
            } else {
                runOnUiThread { Toast.makeText(this, "Không thể kết nối sản phẩm từ CH Play", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
            if (!purchase.isAcknowledged) {
                val acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build()
                billingClient.acknowledgePurchase(acknowledgePurchaseParams) { billingResult ->
                    if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                        savePremiumStatus(true)
                    }
                }
            } else {
                savePremiumStatus(true)
            }
        }
    }

    private fun queryPurchasesOnLaunch() {
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
        ) { billingResult, purchaseList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                val hasPremium = purchaseList.any { it.products.contains(premiumProductId) && it.purchaseState == Purchase.PurchaseState.PURCHASED }
                savePremiumStatus(hasPremium)
            }
        }
    }

    private fun savePremiumStatus(isPremium: Boolean) {
        val sharedPref = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        sharedPref.edit().putBoolean("is_premium", isPremium).apply()
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

        val tvAccountEmail = view.findViewById<TextView>(R.id.tvSheetAccountEmail)
        val tvAccountUid = view.findViewById<TextView>(R.id.tvSheetAccountUid)
        val btnGoogleAuth = view.findViewById<MaterialButton>(R.id.btnSheetGoogleAuth)
        val switchAutoSync = view.findViewById<SwitchMaterial>(R.id.switchAutoSync)
        val spinnerSmsLimit = view.findViewById<Spinner>(R.id.spinnerSmsLimit)
        
        view.findViewById<Button>(R.id.btnMenuBuyPremium)?.setOnClickListener { launchBillingFlow() }

        val user = auth.currentUser
        if (user != null) {
            tvAccountEmail.text = user.email
            tvAccountUid.text = "Mã đồng bộ (UID): ${user.uid.take(10)}..."
            btnGoogleAuth.text = "Đăng xuất / Đổi tài khoản"
            btnGoogleAuth.setBackgroundColor(Color.parseColor("#FF3B30"))
        } else {
            tvAccountEmail.text = "Chưa đăng nhập"
            tvAccountUid.text = "Đăng nhập cùng 1 Gmail trên các máy để đồng bộ"
            btnGoogleAuth.text = "Đăng nhập với Google"
            btnGoogleAuth.setBackgroundColor(Color.parseColor("#0381FE"))
        }

        btnGoogleAuth.setOnClickListener {
            if (auth.currentUser != null) {
                auth.signOut()
                googleSignInClient.signOut().addOnCompleteListener {
                    firestoreListener?.remove()
                    smsAdapter.submitList(emptyList())
                    applySyncModeAndUpdateUI()
                    dialog.dismiss()
                    Toast.makeText(this, "Đã đăng xuất tài khoản", Toast.LENGTH_SHORT).show()
                }
            } else {
                dialog.dismiss()
                signInLauncher.launch(googleSignInClient.signInIntent)
            }
        }

        switchAutoSync.isChecked = SyncPrefs.isAutoSync(this)
        switchAutoSync.setOnCheckedChangeListener { _, isChecked ->
            SyncPrefs.setAutoSync(this, isChecked)
            applySyncModeAndUpdateUI()
        }

        val limitOptions = arrayOf("10 tin nhắn", "20 tin nhắn", "30 tin nhắn", "50 tin nhắn")
        val limitValues = intArrayOf(10, 20, 30, 50)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, limitOptions)
        spinnerSmsLimit.adapter = adapter
        spinnerSmsLimit.setSelection(limitValues.indexOf(SyncPrefs.getSmsLimit(this)).takeIf { it >= 0 } ?: 3)

        spinnerSmsLimit.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                SyncPrefs.setSmsLimit(this@MainActivity, limitValues[position])
                applySyncModeAndUpdateUI()
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
        dialog.show()
    }

    private fun applySyncModeAndUpdateUI() {
        val user = auth.currentUser
        val isAuto = SyncPrefs.isAutoSync(this)
        if (user == null) {
            tvSubtitle.text = "Chưa kết nối tài khoản"
            tvSyncModeStatus.text = "Trạng thái: Ngắt kết nối"
            statusDot.setBackgroundResource(android.R.drawable.presence_offline)
            tvEmptyState.visibility = View.VISIBLE
            return
        }
        tvSubtitle.text = "Tài khoản: ${user.email}"
        if (isAuto) {
            tvSyncModeStatus.text = "Chế độ: Tự động đồng bộ"
            statusDot.setBackgroundResource(android.R.drawable.presence_online)
            startRealtimeListener(user.uid)
        } else {
            tvSyncModeStatus.text = "Chế độ: Đồng bộ thủ công"
            statusDot.setBackgroundResource(android.R.drawable.presence_away)
            firestoreListener?.remove()
            firestoreListener = null
            loadOnceFromCacheOrServer(user.uid, Source.DEFAULT)
        }
    }

    private fun startRealtimeListener(uid: String) {
        firestoreListener?.remove()
        firestoreListener = db.collection("users").document(uid).collection("sms_list")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(SyncPrefs.getSmsLimit(this).toLong())
            .addSnapshotListener { snapshots, error ->
                if (error == null && snapshots != null) renderSmsList(snapshots.documents)
            }
    }

    private fun loadOnceFromCacheOrServer(uid: String, source: Source) {
        db.collection("users").document(uid).collection("sms_list")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(SyncPrefs.getSmsLimit(this).toLong())
            .get(source).addOnSuccessListener { result -> renderSmsList(result.documents) }
    }

    private fun renderSmsList(documents: List<DocumentSnapshot>) {
        smsAdapter.submitList(documents)
        tvEmptyState.visibility = if (documents.isEmpty()) View.VISIBLE else View.GONE
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
            val timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.DATE))
            val sender = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)) ?: "Unknown"
            batch.set(colRef.document("${timestamp}_${sender.hashCode()}"), hashMapOf("sender" to sender, "body" to cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)) ?: "", "timestamp" to timestamp, "deviceModel" to "${Build.MANUFACTURER} ${Build.MODEL}", "syncedAt" to System.currentTimeMillis()))
        } while (cursor.moveToNext())
        cursor.close()
        batch.commit().addOnCompleteListener { btnResync.isEnabled = true; if (it.isSuccessful && !SyncPrefs.isAutoSync(this)) manualRefreshFromCloud() }
    }

    private fun manualRefreshFromCloud() {
        val user = auth.currentUser ?: return
        btnRefresh.isEnabled = false
        db.collection("users").document(user.uid).collection("sms_list")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(SyncPrefs.getSmsLimit(this).toLong())
            .get(Source.SERVER).addOnCompleteListener { btnRefresh.isEnabled = true; if (it.isSuccessful && it.result != null) renderSmsList(it.result.documents) }
    }

    private val signInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(result.data).getResult(ApiException::class.java)!!
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
