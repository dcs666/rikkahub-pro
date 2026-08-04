package me.rerere.rikkahub.ui.pages.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.fts.MessageSearchResult
import me.rerere.rikkahub.data.db.fts.MessageSearchSort
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.ui.hooks.readStringPreference
import me.rerere.rikkahub.ui.hooks.writeStringPreference

private const val SORT_ORDER_PREF_KEY = "search_page_sort_order"

class SearchVM(
    private val context: Application,
    private val conversationRepo: ConversationRepository,
) : ViewModel() {
    private val _searchQuery = MutableStateFlow("")

    var searchQuery by mutableStateOf("")
        private set
    var sortOrder by mutableStateOf(
        runCatching {
            MessageSearchSort.valueOf(
                context.readStringPreference(SORT_ORDER_PREF_KEY, MessageSearchSort.RELEVANCE.name)!!
            )
        }.getOrDefault(MessageSearchSort.RELEVANCE)
    )
        private set
    var results by mutableStateOf<List<MessageSearchResult>>(emptyList())
        private set
    var isLoading by mutableStateOf(false)
        private set
    var isRebuilding by mutableStateOf(false)
        private set
    var rebuildProgress by mutableStateOf(0 to 0)
        private set

    init {
        viewModelScope.launch {
            _searchQuery
                .debounce(300L)
                .collectLatest { query -> performSearch(query) }
        }
    }

    fun onQueryChange(query: String) {
        searchQuery = query
        _searchQuery.value = query
    }

    fun onSortChange(sort: MessageSearchSort) {
        if (sortOrder == sort) return
        sortOrder = sort
        context.writeStringPreference(SORT_ORDER_PREF_KEY, sort.name)
        viewModelScope.launch {
            performSearch(searchQuery)
        }
    }

    fun search() {
        viewModelScope.launch {
            performSearch(searchQuery)
        }
    }

    fun rebuildIndex() {
        viewModelScope.launch {
            isRebuilding = true
            rebuildProgress = 0 to 0
            try {
                conversationRepo.rebuildAllIndexes { current, total ->
                    rebuildProgress = current to total
                }
            } finally {
                isRebuilding = false
            }
        }
    }

    private suspend fun performSearch(query: String) {
        if (query.isBlank()) {
            results = emptyList()
            loadedCount = 0
            activeQuery = ""
            hasMore = false
            return
        }
        isLoading = true
        activeQuery = query
        try {
            val page = conversationRepo.searchMessages(query, sortOrder, limit = PAGE_SIZE, offset = 0)
            results = page
            loadedCount = page.size
            hasMore = page.size >= PAGE_SIZE
        } finally {
            isLoading = false
        }
    }

    /** 滚动接近底部时加载下一页；与当前查询/排序不匹配的过期调用直接忽略。 */
    fun loadMore() {
        if (isLoading || isLoadingMore || !hasMore) return
        val query = activeQuery
        if (query.isBlank()) return
        val sortAtCall = sortOrder
        viewModelScope.launch {
            isLoadingMore = true
            try {
                val page = conversationRepo.searchMessages(
                    query, sortAtCall, limit = PAGE_SIZE, offset = loadedCount
                )
                // 加载期间查询/排序可能已变，丢弃过期页
                if (query != activeQuery || sortAtCall != sortOrder) return@launch
                results = results + page
                loadedCount += page.size
                hasMore = page.size >= PAGE_SIZE
            } finally {
                isLoadingMore = false
            }
        }
    }
}
