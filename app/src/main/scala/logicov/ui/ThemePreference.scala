package logicov.ui

enum ThemePreference(val label: String) {
  case System extends ThemePreference("Device theme")
  case Light extends ThemePreference("Light")
  case Dark extends ThemePreference("Dark")
}
