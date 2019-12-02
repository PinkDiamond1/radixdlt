package com.radixdlt.tempo;

import com.google.common.collect.ImmutableSet;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.radixdlt.common.AID;
import com.radixdlt.common.EUID;
import com.radixdlt.ledger.Ledger;
import com.radixdlt.ledger.LedgerObservation;
import com.radixdlt.tempo.consensus.Consensus;
import com.radixdlt.tempo.delivery.LazyRequestDeliverer;
import com.radixdlt.tempo.discovery.AtomDiscoverer;
import com.radixdlt.tempo.store.LedgerEntryStore;
import com.radixdlt.tempo.store.LedgerEntryStoreView;
import org.radix.logging.Logger;
import org.radix.logging.Logging;
import org.radix.modules.Modules;
import org.radix.network2.addressbook.Peer;

import java.io.Closeable;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * The Tempo implementation of a ledger.
 */
public final class Tempo implements Ledger, Closeable {
	private static final Logger log = Logging.getLogger("tempo");
	private static final int INBOUND_QUEUE_CAPACITY = 16384;

	private final EUID self;
	private final LedgerEntryStore ledgerEntryStore;
	private final Consensus consensus;

	private final Set<AtomDiscoverer> atomDiscoverers;
	private final LazyRequestDeliverer requestDeliverer;
	private final Set<LedgerEntryObserver> observers; // TODO external ledgerObservations and internal observers is ambiguous

	private final BlockingQueue<LedgerObservation> ledgerObservations;

	@Inject
	public Tempo(
		@Named("self") EUID self,
		LedgerEntryStore ledgerEntryStore,
		Consensus consensus,
		Set<AtomDiscoverer> atomDiscoverers,
		LazyRequestDeliverer requestDeliverer,
		Set<LedgerEntryObserver> observers
	) {
		this.self = Objects.requireNonNull(self);
		this.ledgerEntryStore = Objects.requireNonNull(ledgerEntryStore);
		this.consensus = Objects.requireNonNull(consensus);
		this.atomDiscoverers = Objects.requireNonNull(atomDiscoverers);
		this.requestDeliverer = Objects.requireNonNull(requestDeliverer);
		this.observers = Objects.requireNonNull(observers);

		this.ledgerObservations = new LinkedBlockingQueue<>(INBOUND_QUEUE_CAPACITY);

		// hook up components
		for (AtomDiscoverer atomDiscoverer : this.atomDiscoverers) {
			atomDiscoverer.addListener(this::onDiscovered);
		}
	}

	@Override
	public LedgerObservation observe() throws InterruptedException {
		return this.ledgerObservations.take();
	}

	private void onDiscovered(Set<AID> aids, Peer peer) {
		requestDeliverer.deliver(aids, ImmutableSet.of(peer)).forEach((aid, future) -> future.thenAccept(result -> {
			if (result.isSuccess()) {
				injectObservation(LedgerObservation.adopt(result.getLedgerEntry()));
			}
		}));
	}

	private void injectObservation(LedgerObservation observation) {
		if (!this.ledgerObservations.add(observation)) {
			// TODO more graceful queue full handling
			log.error("Atom observations queue full");
		}
	}

	public void start() {
		Modules.put(LedgerEntryStoreView.class, this.ledgerEntryStore);
	}

	@Override
	public void close() {
		Modules.remove(LedgerEntryStoreView.class);
		this.ledgerEntryStore.close();
		this.requestDeliverer.close();
	}
}
