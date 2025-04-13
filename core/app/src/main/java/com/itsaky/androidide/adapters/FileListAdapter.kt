package com.itsaky.androidide.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.itsaky.androidide.R
import java.io.File

class FileListAdapter(
    private var files: List<File>,
    private val onFileClickListener: (File) -> Unit
) : RecyclerView.Adapter<FileListAdapter.FileViewHolder>() {

    // ViewHolder class to hold the views for each item
    class FileViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        // Correctly reference the TextView with the ID 'file_name' from the layout
        val fileNameText: TextView = view.findViewById(R.id.file_name)
        // You might also want to reference the ImageView if you plan to change the icon
        // val fileIcon: ImageView = view.findViewById(R.id.file_icon)
    }

    // Called when RecyclerView needs a new ViewHolder of the given type to represent an item.
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        // Inflate the layout for a single list item
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.layout_file_item_list, parent, false)
        // Create and return a new FileViewHolder
        return FileViewHolder(view)
    }

    // Called by RecyclerView to display the data at the specified position.
    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        // Get the file object for the current position
        val file = files[position]
        // Set the text of the TextView to the file's name (or path, adjust as needed)
        holder.fileNameText.text = file.name // Changed from file.absolutePath to file.name for better readability
        // Set an OnClickListener on the item view to handle clicks
        holder.itemView.setOnClickListener {
            onFileClickListener(file)
        }
        // Here you could also set the file icon based on the file type if needed
        // holder.fileIcon.setImageResource(...)
    }

    // Returns the total number of items in the data set held by the adapter.
    override fun getItemCount() = files.size

    // Function to update the list of files and refresh the RecyclerView
    fun updateFiles(newFiles: List<File>) {
        files = newFiles
        // Notify the adapter that the data set has changed so it can redraw the list
        notifyDataSetChanged() // Consider using DiffUtil for better performance
    }

    // Function to get the current list of files held by the adapter
    fun getFiles(): List<File> = files
}