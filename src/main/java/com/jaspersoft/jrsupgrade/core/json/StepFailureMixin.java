package com.jaspersoft.jrsupgrade.core.json;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.jaspersoft.jrsupgrade.core.engine.StepFailure;

/** Jackson mix-in giving {@code StepFailure} a {@code type} discriminator. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = StepFailure.Retryable.class, name = "Retryable"),
  @JsonSubTypes.Type(value = StepFailure.Recoverable.class, name = "Recoverable"),
  @JsonSubTypes.Type(value = StepFailure.Fatal.class, name = "Fatal")
})
interface StepFailureMixin {}
