package com.jaspersoft.jrsupgrade.core.platform;

/**
 * The running processes could not be listed, so whether a Tomcat is running is unknown. Invariant:
 * never thrown for a scan that ran and found nothing; callers that derive a service state from a
 * scan turn this into {@link ServiceController.State#UNKNOWN}, never into {@code STOPPED}, because
 * a stop skipped on a blind scan swaps files under a live server (issue #38).
 */
final class TomcatScanException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  TomcatScanException(String message) {
    super(message);
  }
}
