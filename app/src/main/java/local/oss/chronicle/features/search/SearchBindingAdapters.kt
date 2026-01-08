package local.oss.chronicle.features.search

import androidx.databinding.BindingAdapter
import androidx.recyclerview.widget.RecyclerView
import local.oss.chronicle.data.model.Audiobook
import local.oss.chronicle.features.library.AudiobookSearchAdapter

@BindingAdapter("serverConnectedSearch")
fun bindSearchRecyclerView(
    recyclerView: RecyclerView,
    serverConnected: Boolean,
) {
    val adapter = recyclerView.adapter as AudiobookSearchAdapter
    adapter.setServerConnected(serverConnected)
}

@BindingAdapter("searchBookList")
fun bindSearchRecyclerView(
    recyclerView: RecyclerView,
    data: List<Audiobook>?,
) {
    val adapter = recyclerView.adapter as AudiobookSearchAdapter
    adapter.submitList(data)
}
