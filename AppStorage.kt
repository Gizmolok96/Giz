package com.potolochnik.app.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class AppStorage private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("potolochnik_data", Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val KEY_PROJECTS = "projects"
        private const val KEY_PROJECT_COUNTER = "project_counter"
        private const val KEY_PRICE_LIST = "price_list"
        private const val KEY_WORKERS = "salary_workers"
        private const val KEY_SALARY_HISTORY = "salary_history"

        @Volatile
        private var INSTANCE: AppStorage? = null

        fun getInstance(context: Context): AppStorage {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AppStorage(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    fun getProjects(): MutableList<Project> {
        val json = prefs.getString(KEY_PROJECTS, null) ?: return mutableListOf()
        val type = object : TypeToken<MutableList<Project>>() {}.type
        return try {
            gson.fromJson(json, type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun saveProjects(projects: List<Project>) {
        prefs.edit().putString(KEY_PROJECTS, gson.toJson(projects)).apply()
    }

    fun getProject(id: String): Project? {
        return getProjects().find { it.id == id }
    }

    fun saveProject(project: Project) {
        val projects = getProjects()
        val idx = projects.indexOfFirst { it.id == project.id }
        if (idx >= 0) projects[idx] = project
        else projects.add(project)
        saveProjects(projects)
    }

    /**
     * Атомарное обновление проекта: перечитывает весь список из хранилища,
     * применяет изменение лямбдой к нужному проекту и сохраняет.
     * Гарантирует, что никакой другой компонент не затрёт данные,
     * записанные между чтением и записью у вызывающей стороны.
     */
    fun updateProject(projectId: String, update: (Project) -> Unit) {
        val projects = getProjects()
        val idx = projects.indexOfFirst { it.id == projectId }
        if (idx >= 0) {
            update(projects[idx])
            saveProjects(projects)
        }
    }

    /**
     * Атомарное сохранение одной комнаты: перечитывает проект из хранилища,
     * заменяет только нужную комнату по ID, сохраняет.
     * Остальные комнаты и поля проекта остаются нетронутыми.
     */
    fun saveRoom(projectId: String, room: Room) {
        updateProject(projectId) { proj ->
            val idx = proj.rooms.indexOfFirst { it.id == room.id }
            if (idx >= 0) proj.rooms[idx] = room
        }
    }

    /**
     * Атомарное добавление новой комнаты в проект.
     */
    fun addRoomToProject(projectId: String, room: Room) {
        updateProject(projectId) { proj ->
            proj.rooms.add(room)
        }
    }

    /**
     * Атомарное удаление комнаты из проекта.
     */
    fun removeRoomFromProject(projectId: String, roomId: String) {
        updateProject(projectId) { proj ->
            proj.rooms.removeAll { it.id == roomId }
        }
    }

    fun deleteProject(id: String) {
        val projects = getProjects().filter { it.id != id }
        saveProjects(projects)
    }

    fun nextProjectNumber(): Int {
        val n = prefs.getInt(KEY_PROJECT_COUNTER, 0) + 1
        prefs.edit().putInt(KEY_PROJECT_COUNTER, n).apply()
        return n
    }

    fun getPriceList(): MutableList<PriceListItem> {
        val json = prefs.getString(KEY_PRICE_LIST, null) ?: return mutableListOf()
        val type = object : TypeToken<MutableList<PriceListItem>>() {}.type
        return try {
            gson.fromJson(json, type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun savePriceList(items: List<PriceListItem>) {
        prefs.edit().putString(KEY_PRICE_LIST, gson.toJson(items)).apply()
    }

    fun savePriceListItem(item: PriceListItem) {
        val items = getPriceList()
        val idx = items.indexOfFirst { it.id == item.id }
        if (idx >= 0) items[idx] = item
        else items.add(item)
        savePriceList(items)
    }

    fun deletePriceListItem(id: String) {
        val items = getPriceList().filter { it.id != id }
        savePriceList(items)
    }

    // ─── Workers ───────────────────────────────────────────────────────────────

    fun getWorkers(): MutableList<Worker> {
        val json = prefs.getString(KEY_WORKERS, null) ?: return mutableListOf()
        val type = object : TypeToken<MutableList<Worker>>() {}.type
        return try { gson.fromJson(json, type) ?: mutableListOf() } catch (e: Exception) { mutableListOf() }
    }

    fun saveWorkers(workers: List<Worker>) {
        prefs.edit().putString(KEY_WORKERS, gson.toJson(workers)).apply()
    }

    fun saveWorker(worker: Worker) {
        val list = getWorkers()
        val idx = list.indexOfFirst { it.id == worker.id }
        if (idx >= 0) list[idx] = worker else list.add(worker)
        saveWorkers(list)
    }

    fun deleteWorker(id: String) = saveWorkers(getWorkers().filter { it.id != id })

    // ─── Salary history ────────────────────────────────────────────────────────

    fun getSalaryHistory(): MutableList<SalaryRecord> {
        val json = prefs.getString(KEY_SALARY_HISTORY, null) ?: return mutableListOf()
        val type = object : TypeToken<MutableList<SalaryRecord>>() {}.type
        return try { gson.fromJson(json, type) ?: mutableListOf() } catch (e: Exception) { mutableListOf() }
    }

    fun saveSalaryHistory(list: List<SalaryRecord>) {
        prefs.edit().putString(KEY_SALARY_HISTORY, gson.toJson(list)).apply()
    }

    fun addSalaryRecord(record: SalaryRecord) {
        val list = getSalaryHistory()
        list.add(0, record)
        saveSalaryHistory(list)
    }

    fun deleteSalaryRecord(id: String) = saveSalaryHistory(getSalaryHistory().filter { it.id != id })
}
