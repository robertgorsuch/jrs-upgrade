package com.jaspersoft.jrsupgrade.jrs.api;

import java.net.URI;

/** The server did not answer a non-mutating request; carries the URL and a remediation. */
public class JrsUnreachableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final URI url;
  private final String remediation;

  public JrsUnreachableException(URI url, String message, String remediation, Throwable cause) {
    super(message, cause);
    this.url = url;
    this.remediation = remediation;
  }

  public URI url() {
    return url;
  }

  public String remediation() {
    return remediation;
  }
}
