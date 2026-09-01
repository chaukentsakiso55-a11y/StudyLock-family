package com.cyberpulse.studylock.parent

data class AppFeature(
    val title: String,
    val description: String,
    val badge: String
)

data class AppMetric(
    val label: String,
    val value: String
)

data class AppSpec(
    val name: String,
    val shortName: String,
    val tagline: String,
    val hero: String,
    val primary: Long,
    val secondary: Long,
    val focusLabel: String,
    val logHint: String,
    val features: List<AppFeature>,
    val metrics: List<AppMetric>,
    val about: String
)
