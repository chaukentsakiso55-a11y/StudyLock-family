package com.cyberpulse.studylock.parent

val appSpec = AppSpec(
    name = "StudyLock Parent",
    shortName = "SP",
    tagline = "Support focus with clarity and trust.",
    hero = "Prepare study schedules, family rules and progress views without hidden monitoring.",
    primary = 0xFF6DE7FF,
    secondary = 0xFF9A67FF,
    focusLabel = "Planned study session",
    logHint = "Add a family goal, rule or schedule note",
    features = listOf(
        AppFeature("Pair Student", "Six-digit pairing contracts are ready for Firebase transport.", "PAIR"),
        AppFeature("Start Focus", "Prepare a remote session command from 25 to 300 minutes.", "REMOTE"),
        AppFeature("Schedules", "Plan recurring study windows and durations.", "PLAN"),
        AppFeature("Blocked Apps", "Prepare a clear family-approved distraction list.", "RULES"),
        AppFeature("Progress", "Review the metrics contract for future student sync.", "VIEW"),
        AppFeature("Trust & Privacy", "Keep controls visible, limited and explainable.", "SAFE")
    ),
    metrics = listOf(
        AppMetric("Student", "Not paired"),
        AppMetric("Commands", "Local draft"),
        AppMetric("Metrics", "Awaiting sync"),
        AppMetric("Firebase", "Phase 2")
    ),
    about = "The StudyLock Parent app is the trusted support side of the Cyber Pulse family system. Remote controls remain unavailable until authenticated Firebase pairing and security rules are added."
)
