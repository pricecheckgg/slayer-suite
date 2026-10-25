package com.slayersuite.alerts;

import lombok.Getter;
import lombok.Setter;

@Getter
public class Alert
{
	private final AlertKind kind;
	@Setter
	private String message;
	@Setter
	private Severity severity;
	private final long raisedAt;
	@Setter
	private long lastNotifiedAt;

	Alert(AlertKind kind, String message, Severity severity, long now)
	{
		this.kind = kind;
		this.message = message;
		this.severity = severity;
		this.raisedAt = now;
		this.lastNotifiedAt = now;
	}

	boolean expired(long now)
	{
		return !kind.isSticky() && kind.getTtlMs() > 0 && now - raisedAt > kind.getTtlMs();
	}
}
