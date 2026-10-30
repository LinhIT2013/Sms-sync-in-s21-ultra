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
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
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
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.RelativeLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.android.billingclient.api.*
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
    private val premiumProductId = "app_premium_license" // ID sản phẩm bạn sẽ tạo trên CH Play Console
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. Kiểm tra thời gian dùng thử và trạng thái bản quyền trước
        checkLicenseAndTrialStatus()

        // 2. Khởi tạo Google Play Billing Client
        setupBillingClient()

        // 3. Đăng ký sự kiện nút bấm mua hàng bên ngoài Menu hoặc trên Màn hình khóa
        findViewById<Button>(R.id.btnBuyPremiumLock).setOnClickListener { launchBillingFlow() }
        // findViewById<Button>(R.id.btnMenuBuyPremium)?.setOnClickListener { launchBillingFlow() }
    }

    private fun checkLicenseAndTrialStatus() {
        val sharedPref = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val isPurchased = sharedPref.getBoolean("is_premium", false)
        
        if (isPurchased) {
            // Đã mua bản quyền -> Mở ứng dụng bình thường
            findViewById<RelativeLayout>(R.id.layoutLockScreen).visibility = View.GONE
            return
        }

        // Kiểm tra ngày đầu tiên mở ứng dụng
        var firstLaunchTime = sharedPref.getLong("first_launch_time", 0L)
        if (firstLaunchTime == 0L) {
            firstLaunchTime = System.currentTimeMillis()
            sharedPref.edit().putLong("first_launch_time", firstLaunchTime).apply()
        }

        // Tính số ngày đã trôi qua (5 ngày = 5 * 24 * 60 * 60 * 1000 ms)
        val fiveDaysInMs = 5L * 24 * 60 * 60 * 1000
        val currentTime = System.currentTimeMillis()

        if (currentTime - firstLaunchTime > fiveDaysInMs) {
            // Hết hạn 5 ngày -> Khóa toàn bộ màn hình
            findViewById<RelativeLayout>(R.id.layoutLockScreen).visibility = View.VISIBLE
        } else {
            // Còn trong hạn dùng thử
            findViewById<RelativeLayout>(R.id.layoutLockScreen).visibility = View.GONE
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
                    // Kết nối cổng thanh toán CH Play thành công
                    queryPurchasesOnLaunch()
                }
            }
            override fun onBillingServiceDisconnected() {
                // Kết nối lại nếu bị ngắt giữa chừng
            }
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
                val productDetails = productDetailsList[0]
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
            // Xác thực giao dịch nếu chưa được xác thực (Bắt buộc theo quy định Google)
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
        // Kiểm tra xem lịch sử người dùng này đã từng mua sản phẩm này chưa (Ví dụ khi họ cài lại ứng dụng)
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
                findViewById<RelativeLayout>(R.id.layoutLockScreen).visibility = View.GONE
                Toast.makeText(this, "Kích hoạt bản quyền thành công!", Toast.LENGTH_LONG).show()
            }
        }
    }

    private var firestoreListener: ListenerRegistration? = null
    private val WEB_CLIENT_ID = "419963533121-r7cohbrnuhnp8h732k5s3k3bc21rq0n5.apps.googleusercontent.com"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

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

    private fun showOneUiSettingsSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_oneui_settings, null)
        dialog.setContentView(view)

        val tvAccountEmail = view.findViewById<TextView>(R.id.tvSheetAccountEmail)
        val tvAccountUid = view.findViewById<TextView>(R.id.tvSheetAccountUid)
        val btnGoogleAuth = view.findViewById<MaterialButton>(R.id.btnSheetGoogleAuth)
        val switchAutoSync = view.findViewById<SwitchMaterial>(R.id.switchAutoSync)
        val spinnerSmsLimit = view.findViewById<Spinner>(R.id.spinnerSmsLimit)

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
            val modeMsg = if (isChecked) "Đã bật Tự động đồng bộ" else "Đã chuyển sang Đồng bộ thủ công"
            Toast.makeText(this, modeMsg, Toast.LENGTH_SHORT).show()
        }

        // Cấu hình Spinner chọn giới hạn tin nhắn (10, 20, 30, 50)
        val limitOptions = arrayOf("10 tin nhắn gần nhất", "20 tin nhắn gần nhất", "30 tin nhắn gần nhất", "50 tin nhắn gần nhất")
        val limitValues = intArrayOf(10, 20, 30, 50)
        
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, limitOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerSmsLimit.adapter = adapter

        val currentLimit = SyncPrefs.getSmsLimit(this)
        val selectedIndex = limitValues.indexOf(currentLimit).takeIf { it >= 0 } ?: 3
        spinnerSmsLimit.setSelection(selectedIndex)

        spinnerSmsLimit.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val chosenLimit = limitValues[position]
                if (chosenLimit != SyncPrefs.getSmsLimit(this@MainActivity)) {
                    SyncPrefs.setSmsLimit(this@MainActivity, chosenLimit)
                    applySyncModeAndUpdateUI()
                    Toast.makeText(this@MainActivity, "Đã chọn giới hạn: $chosenLimit tin", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        dialog.show()
    }

    private fun applySyncModeAndUpdateUI() {
        val user = auth.currentUser
        val isAuto = SyncPrefs.isAutoSync(this)

        if (user == null) {
            tvSubtitle.text = "Chưa kết nối tài khoản • Bấm Cài đặt để đăng nhập"
            tvSyncModeStatus.text = "Trạng thái: Ngắt kết nối"
            statusDot.setBackgroundResource(android.R.drawable.presence_offline)
            tvEmptyState.visibility = View.VISIBLE
            return
        }

        tvSubtitle.text = "Tài khoản: ${user.email}"

        if (isAuto) {
            tvSyncModeStatus.text = "Chế độ: Tự động đồng bộ (Real-time)"
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
        val limit = SyncPrefs.getSmsLimit(this).toLong()

        firestoreListener = db.collection("users")
            .document(uid)
            .collection("sms_list")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(limit)
            .addSnapshotListener { snapshots, error ->
                if (error != null || snapshots == null) return@addSnapshotListener
                renderSmsList(snapshots.documents)
            }
    }

    private fun loadOnceFromCacheOrServer(uid: String, source: Source) {
        val limit = SyncPrefs.getSmsLimit(this).toLong()

        db.collection("users")
            .document(uid)
            .collection("sms_list")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(limit)
            .get(source)
            .addOnSuccessListener { result ->
                renderSmsList(result.documents)
            }
    }

    private fun renderSmsList(documents: List<DocumentSnapshot>) {
        smsAdapter.submitList(documents)
        tvEmptyState.visibility = if (documents.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun resyncLocalSmsToCloud(maxCount: Int) {
        val user = auth.currentUser
        if (user == null) {
            Toast.makeText(this, "Hãy mở Cài đặt và đăng nhập Google trước!", Toast.LENGTH_SHORT).show()
            showOneUiSettingsSheet()
            return
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            requestRequiredPermissions()
            return
        }

        btnResync.isEnabled = false
        btnResync.text = "⏳ Đang đẩy..."

        val cursor = contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            null, null,
            "${Telephony.Sms.DATE} DESC LIMIT $maxCount"
        )

        if (cursor == null || !cursor.moveToFirst()) {
            cursor?.close()
            btnResync.isEnabled = true
            btnResync.text = "⬆️ Đồng bộ lại"
            Toast.makeText(this, "Hộp thư đến trống!", Toast.LENGTH_SHORT).show()
            return
        }

        val batch = db.batch()
        val colRef = db.collection("users").document(user.uid).collection("sms_list")
        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
        var count = 0

        val addrIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
        val bodyIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
        val dateIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)

        do {
            val sender = cursor.getString(addrIdx) ?: "Unknown"
            val body = cursor.getString(bodyIdx) ?: ""
            val timestamp = cursor.getLong(dateIdx)
            val docId = "${timestamp}_${sender.hashCode()}"

            val smsData = hashMapOf(
                "sender" to sender,
                "body" to body,
                "timestamp" to timestamp,
                "deviceModel" to deviceName,
                "syncedAt" to System.currentTimeMillis()
            )
            batch.set(colRef.document(docId), smsData)
            count++
        } while (cursor.moveToNext())
        cursor.close()

        batch.commit().addOnCompleteListener { task ->
            btnResync.isEnabled = true
            btnResync.text = "⬆️ Đồng bộ lại"
            if (task.isSuccessful) {
                Toast.makeText(this, "Đã đồng bộ $count tin nhắn lên Cloud!", Toast.LENGTH_SHORT).show()
                if (!SyncPrefs.isAutoSync(this)) manualRefreshFromCloud()
            } else {
                Toast.makeText(this, "Lỗi: ${task.exception?.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun manualRefreshFromCloud() {
        val user = auth.currentUser
        if (user == null) {
            Toast.makeText(this, "Hãy mở Cài đặt và đăng nhập Google trước!", Toast.LENGTH_SHORT).show()
            showOneUiSettingsSheet()
            return
        }

        btnRefresh.isEnabled = false
        btnRefresh.text = "⏳ Đang tải..."

        val limit = SyncPrefs.getSmsLimit(this).toLong()

        db.collection("users")
            .document(user.uid)
            .collection("sms_list")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(limit)
            .get(Source.SERVER)
            .addOnCompleteListener { task ->
                btnRefresh.isEnabled = true
                btnRefresh.text = "🔄 Làm mới"
                if (task.isSuccessful && task.result != null) {
                    renderSmsList(task.result.documents)
                    Toast.makeText(this, "Đã cập nhật danh sách mới nhất!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Lỗi kết nối Server!", Toast.LENGTH_SHORT).show()
                }
            }
    }

    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)!!
            val credential = GoogleAuthProvider.getCredential(account.idToken, null)
            auth.signInWithCredential(credential).addOnCompleteListener(this) { authTask ->
                if (authTask.isSuccessful) {
                    Toast.makeText(this, "Đăng nhập thành công!", Toast.LENGTH_SHORT).show()
                    applySyncModeAndUpdateUI()
                }
            }
        } catch (e: ApiException) {
            Toast.makeText(this, "Lỗi Google Sign-In: Mã ${e.statusCode}", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestRequiredPermissions() {
        val permissions = arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, permissions, 101)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                })
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        firestoreListener?.remove()
    }
}
