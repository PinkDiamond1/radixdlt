package org.radix.network2.addressbook;

import org.radix.events.Events;
import org.radix.network.discovery.BootstrapDiscovery;
import org.radix.network2.messaging.MessageCentral;
import org.radix.properties.RuntimeProperties;

/**
 * Factory for creating a {@link PeerManager}.
 */
public class PeerManagerFactory {

	/**
	 * Create a {@link PeerManager} based on a default configuration.
	 *
	 * @return The newly constructed {@link PeerManager}
	 */
	public PeerManager createDefault(RuntimeProperties properties, AddressBook addressBook, MessageCentral messageCentral, Events events, BootstrapDiscovery bootstrapDiscovery) {
		PeerManagerConfiguration config = PeerManagerConfiguration.fromRuntimeProperties(properties);
		return new PeerManager(config, addressBook, messageCentral, events, bootstrapDiscovery);
	}
}
