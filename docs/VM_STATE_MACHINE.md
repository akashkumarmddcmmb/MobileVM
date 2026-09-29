# MobileVM State Machine & Lifecycle Specification

## 1. State Diagram

```
                 ┌────────────────────────────────┐
                 │            CREATED             │
                 └───────────────┬────────────────┘
                                 │
                                 │ start()
                                 ▼
                 ┌────────────────────────────────┐
                 │            STARTING            │
                 └───────┬────────────────┬───────┘
                         │                │
            Init Success │                │ Failure / Timeout
                         ▼                ▼
┌────────────────────────────────┐   ┌────────────────────────────────┐
│            RUNNING             │   │             ERROR              │
└───────┬────────────────▲───────┘   └────────────────────────────────┘
        │                │
  pause()                │ resume()
        ▼                │
┌────────────────────────────────┐
│             PAUSED             │
└───────┬────────────────────────┘
        │
 stop() │
        ▼
┌────────────────────────────────┐
│            STOPPING            │
└───────┬────────────────────────┘
        │
        ▼
┌────────────────────────────────┐
│            STOPPED             │
└────────────────────────────────┘
```

---

## 2. State Transition Rules

| From State | Trigger | Target State | Cleanup & Resource Actions |
| :--- | :--- | :--- | :--- |
| **CREATED** | `start()` | **STARTING** | Validates RAM, verifies disk files, loads kernel & DTB. |
| **STARTING** | Initialization Complete | **RUNNING** | Spawns CPU worker loop, enables watchdog timer. |
| **STARTING** | Init Failure / OOM | **ERROR** | Closes file descriptors, releases allocated native memory. |
| **RUNNING** | `pause()` | **PAUSED** | Pauses CPU worker, suspends watchdog, preserves RAM. |
| **PAUSED** | `resume()` | **RUNNING** | Resumes CPU worker and resets watchdog timestamp. |
| **RUNNING / PAUSED** | `stop()` | **STOPPING** | Cancels CPU job, flushes disk buffers, detaches USB. |
| **STOPPING** | Shutdown Complete | **STOPPED** | Resets CPU registers, frees guest RAM, closes disk handles. |
| **RUNNING** | Panic / Unhandled Exception | **ERROR** | Records diagnostic log, halts CPU, prevents disk overwrite. |
| **ERROR** | `reset()` | **STOPPED** | Clears error state and returns VM to a clean stopped state. |
