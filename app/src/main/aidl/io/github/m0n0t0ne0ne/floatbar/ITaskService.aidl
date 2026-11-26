package io.github.m0n0t0ne0ne.floatbar;

import android.os.Bundle;

// Runs in a shell-privileged process started by Shizuku.
interface ITaskService {
    // Required by Shizuku to stop the service.
    void destroy() = 16777114;

    // One task per line: taskId|windowingMode|visible|packageName
    String listTasks() = 1;

    // Take a task out of Nothing's pop-up and put it behind everything.
    void moveToBack(int taskId) = 2;

    // Remove a Nothing floating bubble for a task that is already in the background.
    void unbubble(int taskId) = 3;

    // Bring an existing task back in the given windowing mode (102 = Nothing pop-up, 1 = full screen).
    int restore(int taskId, int windowingMode) = 4;

    // Close an app for real: remove its task and force-stop it.
    void close(int taskId, String pkg) = 5;

    // While on, for the managed apps: the pop-up close button closes the app completely, and the
    // minimise button opens the app full screen instead of making a bubble.
    void setBubbleGuard(boolean on) = 6;

    // Packages the guard manages (the apps on the bar), comma separated.
    void setManagedPackages(String csv) = 7;

    // Remove a task without force-stopping its app.
    void removeTask(int taskId) = 8;

    // Snapshot of the screen: {"w": int, "h": int, "mem": SharedMemory with RGBA_8888 pixels}.
    Bundle captureScreen() = 9;
}
