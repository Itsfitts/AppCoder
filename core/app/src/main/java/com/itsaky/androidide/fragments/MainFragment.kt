package com.itsaky.androidide.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.viewModels
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.itsaky.androidide.activities.MainActivity
import com.itsaky.androidide.activities.PreferencesActivity
import com.itsaky.androidide.activities.TerminalActivity
import com.itsaky.androidide.adapters.MainActionsListAdapter
import com.itsaky.androidide.app.BaseApplication
import com.itsaky.androidide.app.BaseIDEActivity
import com.itsaky.androidide.common.databinding.LayoutDialogProgressBinding
import com.itsaky.androidide.databinding.FragmentMainBinding
import com.itsaky.androidide.models.MainScreenAction
import com.itsaky.androidide.preferences.databinding.LayoutDialogTextInputBinding
import com.itsaky.androidide.resources.R.string
import com.itsaky.androidide.tasks.runOnUiThread
import com.itsaky.androidide.utils.DialogUtils
import com.itsaky.androidide.utils.Environment
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.flashSuccess
import com.itsaky.androidide.viewmodel.MainViewModel
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ProgressMonitor
import org.slf4j.LoggerFactory
import java.io.File

class MainFragment : BaseFragment() {

  private val viewModel by viewModels<MainViewModel>(ownerProducer = { requireActivity() })
  private var binding: FragmentMainBinding? = null

  companion object {
    private val log = LoggerFactory.getLogger(MainFragment::class.java)
  }

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?
  ): View {
    binding = FragmentMainBinding.inflate(inflater, container, false)
    return binding!!.root
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)

    val actions = MainScreenAction.all().also { actions ->
      val onClick = { action: MainScreenAction, _: View ->
        when (action.id) {
          MainScreenAction.ACTION_CREATE_PROJECT -> showCreateProject()
          MainScreenAction.ACTION_OPEN_PROJECT -> pickDirectory() // labeled "Open file browser"
          MainScreenAction.ACTION_OPEN_EXISTING_PROJECTS -> showExistingProjectsList() // NEW
          MainScreenAction.ACTION_CLONE_REPO -> cloneGitRepo()
          MainScreenAction.ACTION_OPEN_TERMINAL -> startActivity(
            Intent(requireActivity(), TerminalActivity::class.java)
          )
          MainScreenAction.ACTION_PREFERENCES -> gotoPreferences()
          MainScreenAction.ACTION_DONATE -> BaseApplication.getBaseInstance().openDonationsPage()
          MainScreenAction.ACTION_DOCS -> BaseApplication.getBaseInstance().openDocs()
        }
      }

      actions.forEach { action ->
        action.onClick = onClick

        if (action.id == MainScreenAction.ACTION_OPEN_TERMINAL) {
          action.onLongClick = { _: MainScreenAction, _: View ->
            val intent = Intent(requireActivity(), TerminalActivity::class.java).apply {
              putExtra(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, true)
            }
            startActivity(intent)
            true
          }
        }
      }
    }

    binding!!.actions.adapter = MainActionsListAdapter(actions)
  }

  override fun onDestroyView() {
    super.onDestroyView()
    binding = null
  }

  private fun pickDirectory() {
    pickDirectory(this::openProject)
  }

  private fun showCreateProject() {
    viewModel.setScreen(MainViewModel.SCREEN_TEMPLATE_LIST)
  }

  fun openProject(root: File) {
    (requireActivity() as MainActivity).openProject(root)
  }

  private fun cloneGitRepo() {
    val builder = DialogUtils.newMaterialDialogBuilder(requireContext())
    val binding = LayoutDialogTextInputBinding.inflate(layoutInflater)
    binding.name.setHint(string.git_clone_repo_url)

    builder.setView(binding.root)
    builder.setTitle(string.git_clone_repo)
    builder.setCancelable(true)
    builder.setPositiveButton(string.git_clone) { dialog, _ ->
      dialog.dismiss()
      val url = binding.name.editText?.text?.toString()
      doClone(url)
    }
    builder.setNegativeButton(android.R.string.cancel, null)
    builder.show()
  }

  private fun doClone(repo: String?) {
    if (repo.isNullOrBlank()) {
      log.warn("Unable to clone repo. Invalid repo URL : {}'", repo)
      return
    }

    var url = repo.trim()
    if (!url.endsWith(".git")) {
      url += ".git"
    }

    val builder = DialogUtils.newMaterialDialogBuilder(requireContext())
    val binding = LayoutDialogProgressBinding.inflate(layoutInflater)

    binding.message.visibility = View.VISIBLE

    builder.setTitle(string.git_clone_in_progress)
    builder.setMessage(url)
    builder.setView(binding.root)
    builder.setCancelable(false)

    val repoName = url.substringAfterLast('/').substringBeforeLast(".git")
    val targetDir = File(Environment.PROJECTS_DIR, repoName)

    val progress = GitCloneProgressMonitor(binding.progress, binding.message)
    val coroutineScope = (activity as? BaseIDEActivity?)?.activityScope ?: viewLifecycleScope

    var getDialog: (() -> AlertDialog?)? = null

    val cloneJob = coroutineScope.launch(Dispatchers.IO) {

      val git = try {
        Git.cloneRepository()
          .setURI(url)
          .setDirectory(targetDir)
          .setProgressMonitor(progress)
          .call()
      } catch (err: Throwable) {
        if (!progress.isCancelled) {
          err.printStackTrace()
          withContext(Dispatchers.Main) {
            getDialog?.invoke()?.also { if (it.isShowing) it.dismiss() }
            showCloneError(err)
          }
        }
        null
      }

      try {
        git?.close()
      } finally {
        val success = git != null
        withContext(Dispatchers.Main) {
          getDialog?.invoke()?.also { dialog ->
            if (dialog.isShowing) dialog.dismiss()
            if (success) flashSuccess(string.git_clone_success)
          }
        }
      }
    }

    builder.setPositiveButton(android.R.string.cancel) { iface, _ ->
      iface.dismiss()
      progress.cancel()
      cloneJob.cancel()
    }

    val dialog = builder.show()
    getDialog = { dialog }
  }

  private fun showCloneError(error: Throwable?) {
    if (error == null) {
      flashError(string.git_clone_failed)
      return
    }

    val builder = DialogUtils.newMaterialDialogBuilder(requireContext())
    builder.setTitle(string.git_clone_failed)
    builder.setMessage(error.localizedMessage)
    builder.setPositiveButton(android.R.string.ok, null)
    builder.show()
  }

  private fun gotoPreferences() {
    startActivity(Intent(requireActivity(), PreferencesActivity::class.java))
  }

  /**
   * Collect possible project roots to be resilient to path differences and typos.
   * - Environment.PROJECTS_DIR (the canonical one used by clone)
   * - External app files dir + AndroidIDEProjects (and the common 'l' vs 'I' typo)
   * - Internal files dir + home/AndroidIDEProjects
   */
  private fun resolveProjectRoots(): List<File> {
    val roots = mutableListOf<File>()

    // 1) The canonical dir used elsewhere in the app
    try {
      Environment.PROJECTS_DIR.canonicalFile.let { roots += it }
    } catch (_: Throwable) { /* ignore */ }

    // 2) External app-specific storage
    requireContext().getExternalFilesDir(null)?.let { ext ->
      val base = ext.canonicalFile
      roots += File(base, "AndroidIDEProjects")
      // Workaround common I/l confusion
      roots += File(base, "AndroidlDEProjects")
    }

    // 3) Internal app files (some builds keep projects under $filesDir/home)
    val homeIde = File(requireContext().filesDir, "home/AndroidIDEProjects")
    roots += homeIde

    // Deduplicate and keep only existing directories
    return roots
      .mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
      .distinctBy { it.absolutePath }
      .filter { it.exists() && it.isDirectory }
  }

  private fun showExistingProjectsList() {
    val roots = resolveProjectRoots()

    // Gather project dirs across all roots
    val projectDirs = roots.flatMap { root ->
      root.listFiles()?.filter { it.isDirectory } ?: emptyList()
    }.mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
      .distinctBy { it.absolutePath }
      .sortedBy { it.name.lowercase() }

    if (projectDirs.isEmpty()) {
      // Show a helpful debug so we know what was scanned
      val scanned = if (roots.isEmpty()) "(no valid roots found)"
      else roots.joinToString("\n") { "• ${it.absolutePath}" }

      val builder = DialogUtils.newMaterialDialogBuilder(requireContext())
      builder.setTitle(string.title_existing_projects)
      builder.setMessage("No projects found.\nScanned:\n$scanned")
      builder.setPositiveButton(android.R.string.ok, null)
      builder.show()

      log.info("Existing projects scan: no projects. Roots scanned:\n$scanned")
      return
    }

    val names = projectDirs.map { it.name }.toTypedArray()
    val builder = DialogUtils.newMaterialDialogBuilder(requireContext())
    builder.setTitle(string.title_existing_projects)
    builder.setItems(names) { dialog, which ->
      dialog.dismiss()
      openProject(projectDirs[which])
    }
    builder.setNegativeButton(android.R.string.cancel, null)
    builder.show()
  }

  // TODO(itsaky) : Improve this implementation
  class GitCloneProgressMonitor(
    val progress: LinearProgressIndicator,
    val message: TextView
  ) : ProgressMonitor {

    private var cancelled = false

    fun cancel() {
      cancelled = true
    }

    override fun start(totalTasks: Int) {
      runOnUiThread { progress.max = totalTasks }
    }

    override fun beginTask(title: String?, totalWork: Int) {
      runOnUiThread { message.text = title }
    }

    override fun update(completed: Int) {
      runOnUiThread { progress.progress = completed }
    }

    override fun showDuration(enabled: Boolean) {
      // no-op
    }

    override fun endTask() {}

    override fun isCancelled(): Boolean {
      return cancelled || Thread.currentThread().isInterrupted
    }
  }
}