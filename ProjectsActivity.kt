package com.zamerpro.app.ui.projects

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import com.zamerpro.app.R
import com.zamerpro.app.data.AppStorage
import com.zamerpro.app.data.Project
import com.zamerpro.app.data.ProjectStage
import com.zamerpro.app.databinding.ActivityProjectsBinding
import com.zamerpro.app.ui.project.ProjectActivity
import com.zamerpro.app.ui.pricelist.PriceListActivity
import com.zamerpro.app.ui.salary.SalaryActivity

class ProjectsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProjectsBinding
    private lateinit var storage: AppStorage
    private lateinit var adapter: ProjectAdapter

    private var allProjects: MutableList<Project> = mutableListOf()
    private var selectedStage: ProjectStage? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProjectsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        storage = AppStorage.getInstance(this)

        setupToolbar()
        setupRecyclerView()
        setupStatusTabs()
        setupCreateButton()
    }

    override fun onResume() {
        super.onResume()
        loadProjects()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        binding.btnPriceList.setOnClickListener {
            PriceListActivity.start(this)
        }
        binding.btnSalary.setOnClickListener {
            SalaryActivity.start(this)
        }
    }

    private fun setupRecyclerView() {
        adapter = ProjectAdapter { project ->
            ProjectActivity.start(this, project.id)
        }
        binding.rvProjects.layoutManager = LinearLayoutManager(this)
        binding.rvProjects.adapter = adapter
    }

    private fun setupStatusTabs() {
        ProjectStage.values().forEach { stage ->
            val tab = binding.statusTabs.newTab()
            val tabView = layoutInflater.inflate(R.layout.item_stage_tab, null)
            tabView.findViewById<View>(R.id.tabDot).background.setTint(Color.parseColor(stage.colorHex))
            tabView.findViewById<TextView>(R.id.tabLabel).text = stage.label
            tab.customView = tabView
            binding.statusTabs.addTab(tab)
        }

        binding.statusTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                val idx = tab?.position ?: return
                selectedStage = ProjectStage.values()[idx]
                filterProjects()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {
                selectedStage = null
                filterProjects()
            }
        })
    }

    private fun setupCreateButton() {
        binding.btnCreateProject.setOnClickListener {
            createNewProject()
        }
    }

    private fun loadProjects() {
        allProjects = storage.getProjects()
        updateTitle()
        filterProjects()
    }

    private fun filterProjects() {
        val filtered = if (selectedStage == null) allProjects
        else allProjects.filter { it.stage == selectedStage }

        adapter.submitList(filtered.toMutableList())

        val isEmpty = filtered.isEmpty()
        binding.emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.rvProjects.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun updateTitle() {
        binding.tvTitle.text = "${getString(R.string.projects_title)} ${allProjects.size}"
    }

    private fun createNewProject() {
        val project = Project(number = storage.nextProjectNumber())
        storage.saveProject(project)
        ProjectActivity.start(this, project.id)
    }
}
