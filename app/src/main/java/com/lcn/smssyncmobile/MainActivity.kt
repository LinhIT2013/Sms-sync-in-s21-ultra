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
import com.android.billingclient.api.*
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

class MainActivity : AppCompatActivity(), PurchasesUpdatedListener {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var billingClient: BillingClient

    private lateinit var tvSubtitle: TextView
    private lateinit var tvTrialStatus: TextView
    private lateinit var tvSyncModeStatus: TextView
    private lateinit var statusDot: View
    private lateinit var btnResync: MaterialButton
    private lateinit var btnRefresh: MaterialButton
    private lateinit var rvSmsList: RecyclerView
    private lateinit var tvEmptyState: TextView
    private lateinit var smsAdapter: SmsAdapter

    private var firestoreListener: ListenerRegistration? = null
    private val WEB_CLIENT_ID = "419963533121-r7cohbrnuhnp8h732k5s3k3bc21rq0n5.apps.googleusercontent.com"
    private val PRODUCT_ID_PRO = "sms_sync_pro_lifetime"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvSubtitle = findViewById(R.id.tvSubtitle)
        tvTrialStatus = findViewById(R.id.tvTrialStatus)
        tvSyncModeStatus = findViewById(R.id.tvSyncModeStatus)
        statusDot = findViewById(R.id.statusDot)
        btnResync = findViewById(R.id.btnResync)
        btnRefresh = findViewById(R.id.btnRefresh)
        rvSmsList = findViewById(R.id.rvSmsList)
        tvEmptyState = findViewById(R.id.tvEmptyState)
        val btnOpenSettings = findViewById<Button>(R.id.btnOpenSettings)

        // Khởi tạo và kết nối Google Play Billing Client chuẩn
        billingClient = BillingClient.newBuilder(this)
            .setListener(this)
            .enablePendingPurchases()
            .build()
        connectGooglePlayBilling()

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
            if (SyncPrefs.isTrialExpired(this) && !SyncPrefs.isPro(this)) {
                Toast.makeText(this, "Đã hết 90 ngày dùng thử! Vui lòng mua ứng dụng qua Google Play để tiếp tục.", Toast.LENGTH_LONG).show()
                showOneUiSettingsSheet()
                return@setOnClickListener
            }
            val limit = SyncPrefs.getSmsLimit(this)
            resyncLocalSmsToCloud(maxCount = limit)
        }
        
        btnRefresh.setOnClickListener { 
            if (SyncPrefs.isTrialExpired(this) && !SyncPrefs.isPro(this)) {
                Toast.makeText(this, "Đã hết 90 ngày dùng thử! Vui lòng mua ứng dụng qua Google Play để tiếp tục.", Toast.LENGTH_LONG).show()
                showOneUiSettingsSheet()
                return@setOnClickListener
            }
            manualRefreshFromCloud()
        }

        updateTrialAndSyncStatusUI()
    }

    private fun connectGooglePlayBilling() {
        if (!billingClient.isReady) {
            billingClient.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(billingResult: BillingResult) {
                    if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                        checkExistingPurchases()
                    }
                }
                override fun onBillingServiceDisconnected() {
                    // Thử kết nối lại nếu Google Play Service ngắt
                }
            })
        }
    }

    private fun checkExistingPurchases() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        billingClient.queryPurchasesAsync(params) { billingResult, purchases ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                var hasPro = false
                for (purchase in purchases) {
                    if (purchase.products.contains(PRODUCT_ID_PRO) && purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                        hasPro = true
                        if (!purchase.isAcknowledged) {
                            acknowledgePurchase(purchase)
                        }
                    }
                }
                if (hasPro != SyncPrefs.isPro(this)) {
                    SyncPrefs.setPro(this, hasPro)
                    runOnUiThread { updateTrialAndSyncStatusUI() }
                }
            }
        }
    }

    private fun acknowledgePurchase(purchase: Purchase) {
        val acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        billingClient.acknowledgePurchase(acknowledgePurchaseParams) { _ -> }
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: MutableList<Purchase>?) {
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (purchase in purchases) {
                if (purchase.products.contains(PRODUCT_ID_PRO) && purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                    SyncPrefs.setPro(this, true)
                    acknowledgePurchase(purchase)
                    Toast.makeText(this, "🎉 Mua ứng dụng thành công qua Google Play!", Toast.LENGTH_LONG).show()
                    updateTrialAndSyncStatusUI()
                }
            }
        } else if (billingResult.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
            Toast.makeText(this, "Người dùng đã hủy giao dịch.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Lỗi giao dịch Google Play: ${billingResult.debugMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchPurchaseFlow() {
        if (!billingClient.isReady) {
            Toast.makeText(this, "Đang kết nối đến Google Play Store, vui lòng thử lại sau vài giây...", Toast.LENGTH_SHORT).show()
            connectGooglePlayBilling()
            return
        }

        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRODUCT_ID_PRO)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        )
        val params = QueryProductDetailsParams.newBuilder().setProductList(productList).build()

        billingClient.queryProductDetailsAsync(params) { billingResult, productDetailsList ->
            runOnUiThread {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && !productDetailsList.isNullOrEmpty()) {
                    val productDetails = productDetailsList[0]
                    val productDetailsParamsList = listOf(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                            .setProductDetails(productDetails)
                            .build()
                    )
                    val billingFlowParams = BillingFlowParams.newBuilder()
                        .setProductDetailsParamsList(productDetailsParamsList)
                        .build()
                    billingClient.launchBillingFlow(this, billingFlowParams)
                } else {
                    Toast.makeText(this, "Không tìm thấy sản phẩm trên Google Play Console (Mã lỗi: ${billingResult.responseCode})", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun updateTrialAndSyncStatusUI() {
        val isPro = SyncPrefs.isPro(this)
        val isExpired = SyncPrefs.isTrialExpired(this)
        val remainingDays = SyncPrefs.getRemainingDays(this)

        if (isPro) {
            tvTrialStatus.text = "👑 Trạng thái: Đã mua bản quyền Pro (Vĩnh viễn)"
            tvTrialStatus.setTextColor(Color.parseColor("#34C759"))
        } else if (isExpired) {
            tvTrialStatus.text = "⛔ Trạng thái: Đã hết 90 ngày dùng thử (Đã khóa)"
            tvTrialStatus.setTextColor(Color.parseColor("#FF3B30"))
        } else {
            tvTrialStatus.text = "⏳ Trạng thái dùng thử: Còn lại $remainingDays ngày"
            tvTrialStatus.setTextColor(Color.parseColor("#FF9500"))
        }

        applySyncModeAndUpdateUI()
    }

    private fun showOneUiSettingsSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_oneui_settings, null)
        dialog.setContentView(view)

        val tvLicenseStatus = view.findViewById<TextView>(R.id.tvLicenseStatus)
        val tvLicenseDesc = view.findViewById<TextView>(R.id.tvLicenseDesc)
        val btnBuyPro = view.findViewById<MaterialButton>(R.id.btnBuyPro)

        val tvAccountEmail = view.findViewById<TextView>(R.id.tvSheetAccountEmail)
        val tvAccountUid = view.findViewById<TextView>(R.id.tvSheetAccountUid)
        val btnGoogleAuth = view.findViewById<MaterialButton>(R.id.btnSheetGoogleAuth)
        val switchAutoSync = view.findViewById<SwitchMaterial>(R.id.switchAutoSync)
        val spinnerSmsLimit = view.findViewById<Spinner>(R.id.spinnerSmsLimit)

        val isPro = SyncPrefs.isPro(this)
        val isExpired = SyncPrefs.isTrialExpired(this)
        val remainingDays = SyncPrefs.getRemainingDays(this)

        if (isPro) {
            tvLicenseStatus.text = "👑 Đã sở hữu bản quyền Pro vĩnh viễn"
            tvLicenseDesc.text = "Ứng dụng đã được kích hoạt đầy đủ tính năng."
            btnBuyPro.text = "✅ Đã mua ứng dụng qua Google Play"
            btnBuyPro.setBackgroundColor(Color.parseColor("#34C759"))
            btnBuyPro.isEnabled = false
        } else if (isExpired) {
            tvLicenseStatus.text = "⛔ Hết hạn dùng thử 90 ngày"
            tvLicenseDesc.text = "Chức năng đồng bộ đã bị khóa. Vui lòng mua vĩnh viễn qua Google Play để mở khóa."
            btnBuyPro.text = "🛒 Mua ngay qua Google Play"
            btnBuyPro.setBackgroundColor(Color.parseColor("#FF3B30"))
        } else {
            tvLicenseStatus.text = "⏳ Dùng thử: Còn lại $remainingDays ngày"
            tvLicenseDesc.text = "Mua ứng dụng 1 lần qua Google Play để sử dụng vĩnh viễn không giới hạn."
            btnBuyPro.text = "🛒 Mua vĩnh viễn qua Google Play"
            btnBuyPro.setBackgroundColor(Color.parseColor("#34C759"))
        }

        btnBuyPro.setOnClickListener {
            if (!isPro) {
                launchPurchaseFlow()
            }
        }

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
                    updateTrialAndSyncStatusUI()
                    dialog.dismiss()
                    Toast.makeText(this, "Đã đăng xuất tài khoản", Toast.LENGTH_SHORT).show()
                }
            } else {
                dialog.dismiss()
                signInLauncher.launch(googleSignInClient.signInIntent)
            }
        }

        if (isExpired && !isPro) {
            switchAutoSync.isChecked = false
            switchAutoSync.isEnabled = false
            SyncPrefs.setAutoSync(this, false)
        } else {
            switchAutoSync.isEnabled = true
            switchAutoSync.isChecked = SyncPrefs.isAutoSync(this)
            switchAutoSync.setOnCheckedChangeListener { _, isChecked ->
                SyncPrefs.setAutoSync(this, isChecked)
                updateTrialAndSyncStatusUI()
                val modeMsg = if (isChecked) "Đã bật Tự động đồng bộ" else "Đã chuyển sang Đồng bộ thủ công"
                Toast.makeText(this, modeMsg, Toast.LENGTH_SHORT).show()
            }
        }

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
                    updateTrialAndSyncStatusUI()
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
        val isExpired = SyncPrefs.isTrialExpired(this)
        val isPro = SyncPrefs.isPro(this)

        if (isExpired && !isPro) {
            tvSyncModeStatus.text = "Chế độ: Đã khóa (Hết hạn dùng thử 90 ngày)"
            statusDot.setBackgroundResource(android.R.drawable.presence_busy)
            firestoreListener?.remove()
            firestoreListener = null
            smsAdapter.submitList(emptyList())
            tvEmptyState.visibility = View.VISIBLE
            tvEmptyState.text = "⚠️ Ứng dụng đã hết hạn dùng thử 90 ngày.\nVui lòng mua ứng dụng qua Google Play để mở khóa!"
            return
        }

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
        if (documents.isEmpty()) {
            tvEmptyState.text = "Chưa có tin nhắn nào được đồng bộ"
        }
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
            .orderBy("timestamp", Query.Duration.DESCENDING)
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
                    updateTrialAndSyncStatusUI()
                }
            }
        } catch (e: ApiException) {
            Toast.makeText(this, "Lỗi Google Play Billing kết nối thất bại hoặc cấu hình sai.", Toast.LENGTH_LONG).show()
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
