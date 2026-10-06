package dev.librepocket.entry

/**
 * D06 最小版系統入口種類（P6 範圍子集）。
 *
 * 本期只收斂 6 個入口：Launcher / Shortcut / Tile / Share /
 * 通知回覆 / Assist（預設助手）。Widget 與語音喚起不在本期，
 * 分別延後到 D06 後續與 D10，不在本包出現。
 */
enum class EntryKind {
  LAUNCHER,
  SHORTCUT,
  TILE,
  SHARE,
  NOTIFICATION_REPLY,
  ASSIST,
}
