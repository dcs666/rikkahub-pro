package me.rerere.rikkahub.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * [A 滚动感知降级] 当前聊天列表是否正在滚动。
 *
 * 对标 Telegram RecyclerListView 的 checkStopHeavyOperations：
 * 用户滚动时全局暂停昂贵操作（shimmer 动画、定时更新等），
 * 滚动停止自动恢复——保证滚动本身 60fps，重活在滚完后再补。
 *
 * 由 ChatList 在 LazyColumn 外层提供（LazyListState.isScrollInProgress 驱动），
 * 子组件（消息气泡/思维链卡片等）读取后自行降级。
 */
val LocalIsScrolling = staticCompositionLocalOf { false }
