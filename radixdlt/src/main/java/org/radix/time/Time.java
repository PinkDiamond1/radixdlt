package org.radix.time;

import org.radix.modules.Modules;
import org.radix.properties.RuntimeProperties;

public final class Time {
	public static final int MAXIMUM_DRIFT = 30;

	public static void start(RuntimeProperties properties) {
		if (properties.get("ntp", false)) {
			Modules.put(NtpService.class, new NtpService(properties.get("ntp.pool")));
		} else {
			Modules.put(NtpService.class, new NtpService(null));
		}
	}

	public static long currentTimestamp() {
		return Modules.isAvailable(NtpService.class) ? Modules.get(NtpService.class).getUTCTimeMS() : System.currentTimeMillis();
	}
}
