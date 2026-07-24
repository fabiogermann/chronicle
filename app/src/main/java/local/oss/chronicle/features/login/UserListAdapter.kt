package local.oss.chronicle.features.login

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.facebook.drawee.backends.pipeline.Fresco
import com.facebook.imagepipeline.request.ImageRequest
import local.oss.chronicle.data.sources.plex.model.PlexUser
import local.oss.chronicle.databinding.ListItemUserBinding

class UserListAdapter(val clickListener: UserClickListener) :
    ListAdapter<PlexUser, UserListAdapter.UserViewHolder>(UserDiffCallback()) {
    override fun onBindViewHolder(
        holder: UserViewHolder,
        position: Int,
    ) {
        holder.bind(getItem(position), clickListener)
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): UserViewHolder {
        return UserViewHolder.from(parent)
    }

    class UserViewHolder private constructor(val binding: ListItemUserBinding) :
        RecyclerView.ViewHolder(binding.root) {
            fun bind(
                user: PlexUser,
                clickListener: UserClickListener,
            ) {
                binding.user = user
                // A null image request renders the XML placeholder (ic_person_white)
                val imageRequest =
                    user.thumb
                        .takeIf { it.isNotEmpty() }
                        ?.let(ImageRequest::fromUri)
                binding.userThumb.controller =
                    Fresco.newDraweeControllerBuilder()
                        .setImageRequest(imageRequest)
                        .setOldController(binding.userThumb.controller)
                        .build()
                binding.clickListener = clickListener
                binding.executePendingBindings()
            }

            companion object {
                fun from(parent: ViewGroup): UserViewHolder {
                    val layoutInflater = LayoutInflater.from(parent.context)
                    val binding = ListItemUserBinding.inflate(layoutInflater, parent, false)
                    return UserViewHolder(binding)
                }
            }
        }
}

class UserDiffCallback : DiffUtil.ItemCallback<PlexUser>() {
    override fun areItemsTheSame(
        oldItem: PlexUser,
        newItem: PlexUser,
    ): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(
        oldItem: PlexUser,
        newItem: PlexUser,
    ): Boolean {
        return oldItem.title == newItem.title
    }
}
