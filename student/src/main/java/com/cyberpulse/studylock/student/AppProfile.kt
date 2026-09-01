package com.cyberpulse.studylock.student

val appSpec = AppSpec(
    name = "StudyLock Student",
    shortName = "SS",
    tagline = "Your focus. Your progress. Your future.",
    hero = "Build consistent study habits with protected sessions and clear personal progress.",
    primary = 0xFF00E6C7,
    secondary = 0xFF4C8DFF,
    focusLabel = "Student focus session",
    logHint = "Add a goal, homework item or reflection",
    features = listOf(
        AppFeature("Focus Lock", "Run a session between 25 minutes and 5 hours.", "FOCUS"),
        AppFeature("Live Tutor", "Prepare questions for the later safeguarded AI tutor.", "TUTOR"),
        AppFeature("Blocked Apps", "Review the future distraction-control list.", "SHIELD"),
        AppFeature("Study Plan", "Capture tasks and select the next priority.", "PLAN"),
        AppFeature("Progress", "Keep a private record of completed work.", "GROW"),
        AppFeature("Family Pairing", "Pairing UI is prepared; remote sync comes with Firebase.", "LATER")
    ),
    metrics = listOf(
        AppMetric("Minimum", "25 min"),
        AppMetric("Maximum", "5 hours"),
        AppMetric("Data", "Local"),
        AppMetric("Parent sync", "Phase 2")
    ),
    about = "The StudyLock Student app is one half of the Cyber Pulse family focus system. This phase provides local study tools without pretending remote control is active."
)
