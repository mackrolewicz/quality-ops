package com.qualityops.events;

/** Terminal TEST outcome a run can COMPLETE with. Deliberately narrower than the
 *  API's persistent RunStatus (which also has PENDING / RUNNING / CANCELLED). */
public enum RunOutcome { PASSED, FAILED }
