package com.lcn.smssyncmobile

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.DocumentSnapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SmsAdapter(private val onItemClick: (String) -> Unit) :
    RecyclerView.Adapter<SmsAdapter.SmsViewHolder>() {

    private var smsList: List<DocumentSnapshot> = emptyList()
    private val sdf = SimpleDateFormat("dd/MM/yyyy • HH:mm:ss", Locale.getDefault())

    fun submitList(newList: List<DocumentSnapshot>) {
        smsList = newList
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SmsViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_sms_oneui, parent, false)
        return SmsViewHolder(view)
    }

    override fun onBindViewHolder(holder: SmsViewHolder, position: Int) {
        val doc = smsList[position]
        val sender = doc.getString("sender") ?: "Không rõ"
        val body = doc.getString("body") ?: ""
        val model = doc.getString("deviceModel") ?: "Thiết bị Android"
        val time = doc.getLong("timestamp") ?: 0L

        holder.tvSender.text = sender
        holder.tvBody.text = body
        holder.tvDeviceModel.text = "📱 $model"
        holder.tvTime.text = sdf.format(Date(time))

        holder.itemView.setOnClickListener { onItemClick(body) }
    }

    override fun getItemCount(): Int = smsList.size

    class SmsViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvSender: TextView = itemView.findViewById(R.id.tvSender)
        val tvTime: TextView = itemView.findViewById(R.id.tvTime)
        val tvDeviceModel: TextView = itemView.findViewById(R.id.tvDeviceModel)
        val tvBody: TextView = itemView.findViewById(R.id.tvBody)
    }
}
