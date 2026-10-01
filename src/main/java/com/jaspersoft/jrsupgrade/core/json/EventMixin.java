package com.jaspersoft.jrsupgrade.core.json;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.jaspersoft.jrsupgrade.core.event.Event;
import java.util.Optional;

/** Jackson mix-in giving {@code Event} a {@code type} discriminator and an explicit stepId. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = Event.PlanCreated.class, name = "PlanCreated"),
  @JsonSubTypes.Type(value = Event.StepPending.class, name = "StepPending"),
  @JsonSubTypes.Type(value = Event.StepRunning.class, name = "StepRunning"),
  @JsonSubTypes.Type(value = Event.StepRetry.class, name = "StepRetry"),
  @JsonSubTypes.Type(value = Event.StepSucceeded.class, name = "StepSucceeded"),
  @JsonSubTypes.Type(value = Event.StepFailed.class, name = "StepFailed"),
  @JsonSubTypes.Type(value = Event.StepSkipped.class, name = "StepSkipped"),
  @JsonSubTypes.Type(value = Event.StepRolledBack.class, name = "StepRolledBack"),
  @JsonSubTypes.Type(value = Event.StepRollbackFailed.class, name = "StepRollbackFailed"),
  @JsonSubTypes.Type(value = Event.Log.class, name = "Log"),
  @JsonSubTypes.Type(value = Event.RunSucceeded.class, name = "RunSucceeded"),
  @JsonSubTypes.Type(value = Event.RunFailed.class, name = "RunFailed"),
  @JsonSubTypes.Type(value = Event.RunCancelled.class, name = "RunCancelled"),
  @JsonSubTypes.Type(value = Event.RunRolledBack.class, name = "RunRolledBack")
})
interface EventMixin {

  @JsonProperty("stepId")
  Optional<String> stepId();
}
