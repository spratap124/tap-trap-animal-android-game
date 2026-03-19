package com.spratap.taptrapanimal

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ShopAdapter(
    private val animals: List<Animal>,
    private val unlocked: MutableList<Int>,
    private var playerCoins: Int,
    private val onBuy: (Int) -> Unit
) : RecyclerView.Adapter<ShopAdapter.ViewHolder>() {

    fun updateCoins(newCoins: Int) {
        playerCoins = newCoins
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_shop_animal, parent, false)
        return ViewHolder(v)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val animal = animals[position]
        val isOwned = unlocked.contains(position)

        holder.emojiText.text = animal.emoji
        holder.nameText.text = animal.name

        if (isOwned) {
            holder.priceText.text = "✓ Owned"
            holder.priceText.setTextColor(Color.parseColor("#81c784"))
            holder.buyBtn.visibility = View.GONE
            holder.priceText.visibility = View.VISIBLE
            holder.itemView.setBackgroundResource(R.drawable.shop_item_owned_bg)
        } else {
            holder.priceText.text = "${animal.cost} 🪙"
            holder.priceText.setTextColor(Color.parseColor("#aaaaaa"))
            holder.priceText.visibility = View.VISIBLE
            holder.buyBtn.visibility = View.VISIBLE
            holder.buyBtn.isEnabled = playerCoins >= animal.cost
            holder.itemView.setBackgroundResource(R.drawable.shop_item_bg)
            holder.buyBtn.setOnClickListener {
                onBuy(position)
            }
        }
    }

    override fun getItemCount() = animals.size

    class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val emojiText: TextView = v.findViewById(R.id.shopItemEmoji)
        val nameText: TextView = v.findViewById(R.id.shopItemName)
        val priceText: TextView = v.findViewById(R.id.shopItemPrice)
        val buyBtn: Button = v.findViewById(R.id.shopBuyBtn)
    }
}
