package com.potolochnik.app.ui.salary

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.drawable.DrawableCompat
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayoutMediator
import com.potolochnik.app.databinding.ActivitySalaryBinding

class SalaryActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySalaryBinding

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, SalaryActivity::class.java))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySalaryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Расчёт зарплаты"
        binding.toolbar.navigationIcon?.let {
            DrawableCompat.setTint(DrawableCompat.wrap(it).mutate(), Color.WHITE)
        }

        val adapter = SalaryPagerAdapter(this)
        binding.viewPager.adapter = adapter

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, pos ->
            tab.text = when (pos) {
                0 -> "Работники"
                1 -> "Расчёт"
                2 -> "История"
                else -> ""
            }
        }.attach()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    inner class SalaryPagerAdapter(activity: SalaryActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount() = 3
        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> WorkersFragment.newInstance()
            1 -> SalaryCalculationFragment.newInstance()
            2 -> SalaryHistoryFragment.newInstance()
            else -> WorkersFragment.newInstance()
        }
    }
}
