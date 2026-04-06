package com.zamerpro.app.ui.project

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.drawable.DrawableCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayoutMediator
import com.zamerpro.app.R
import com.zamerpro.app.data.AppStorage
import com.zamerpro.app.data.Project
import com.zamerpro.app.databinding.ActivityProjectBinding
import com.zamerpro.app.ui.export.ExportHelper

class ProjectActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProjectBinding
    private lateinit var storage: AppStorage
    lateinit var project: Project

    companion object {
        const val EXTRA_PROJECT_ID = "project_id"

        fun start(context: Context, projectId: String) {
            val intent = Intent(context, ProjectActivity::class.java).apply {
                putExtra(EXTRA_PROJECT_ID, projectId)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProjectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        storage = AppStorage.getInstance(this)
        val projectId = intent.getStringExtra(EXTRA_PROJECT_ID)!!
        project = storage.getProject(projectId)!!

        setupToolbar()
        setupViewPager()
    }

    override fun onResume() {
        super.onResume()
        project = storage.getProject(project.id) ?: return
        updateHeader()
        updateTabTitles()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = project.name
        binding.toolbar.navigationIcon?.let {
            DrawableCompat.setTint(DrawableCompat.wrap(it).mutate(), Color.WHITE)
        }
        updateHeader()
    }

    private fun updateHeader() {
        binding.tvTotal.text = "${project.totalPrice.toInt()} ₽"
        binding.tvAreaPerim.text =
            "S = ${String.format("%.2f", project.totalArea)} кв.м  " +
                    "P = ${String.format("%.2f", project.totalPerimeter)} м"
        supportActionBar?.title = project.name
    }

    private fun setupViewPager() {
        val adapter = ProjectPagerAdapter(this)
        binding.viewPager.adapter = adapter

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.tab_info)
                1 -> "${getString(R.string.tab_rooms)} ${project.rooms.size}"
                2 -> "${getString(R.string.tab_estimate)} ${project.rooms.size}"
                else -> ""
            }
        }.attach()
    }

    private fun updateTabTitles() {
        binding.tabLayout.getTabAt(1)?.text = "${getString(R.string.tab_rooms)} ${project.rooms.size}"
        binding.tabLayout.getTabAt(2)?.text = "${getString(R.string.tab_estimate)} ${project.rooms.size}"
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.project_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
                true
            }
            R.id.action_send -> {
                showSendSheet()
                true
            }
            R.id.action_delete -> {
                showDeleteConfirm()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showSendSheet() {
        val options = arrayOf(
            getString(R.string.send_pdf),
            getString(R.string.send_text),
            getString(R.string.send_txt),
            getString(R.string.send_jpg),
            getString(R.string.send_xml)
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.send_estimate))
            .setItems(options) { _, which ->
                exportProject(which)
            }
            .show()
    }

    private fun exportProject(type: Int) {
        // Баг 1: всегда читаем актуальные данные из хранилища перед экспортом
        project = storage.getProject(project.id) ?: project

        if (type == 0) {
            // PDF — 5 вариантов
            val options = arrayOf(
                "Полная смета + чертежи",
                "Полная смета (без чертежей)",
                "Только итог + чертежи",
                "Только итог (без чертежей)",
                "Только чертежи"
            )
            AlertDialog.Builder(this)
                .setTitle("Вид PDF")
                .setItems(options) { _, which ->
                    try {
                        when (which) {
                            0 -> ExportHelper.exportPdf(this, project)
                            1 -> ExportHelper.exportPdfEstimateOnly(this, project)
                            2 -> ExportHelper.exportPdfSummary(this, project)
                            3 -> ExportHelper.exportPdfSummaryOnly(this, project)
                            4 -> ExportHelper.exportPdfBlueprintsOnly(this, project)
                        }
                    } catch (e: Exception) {
                        Toast.makeText(this, "Ошибка экспорта: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
                .show()
            return
        }
        try {
            when (type) {
                1 -> ExportHelper.exportText(this, project)
                2 -> ExportHelper.exportTxt(this, project)
                3 -> ExportHelper.exportJpg(this, project)
                4 -> ExportHelper.exportXml(this, project)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка экспорта: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showDeleteConfirm() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete))
            .setMessage("Удалить ${project.name}?")
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                storage.deleteProject(project.id)
                finish()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    inner class ProjectPagerAdapter(activity: ProjectActivity) :
        FragmentStateAdapter(activity) {
        override fun getItemCount() = 3
        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> InfoFragment.newInstance(project.id)
            1 -> RoomsFragment.newInstance(project.id)
            2 -> EstimateFragment.newInstance(project.id)
            else -> InfoFragment.newInstance(project.id)
        }
    }
}
