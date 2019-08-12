package com.radixdlt.tempo.sync;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.radixdlt.atoms.AtomStatus;
import com.radixdlt.common.AID;
import com.radixdlt.common.EUID;
import com.radixdlt.serialization.Serialization;
import com.radixdlt.tempo.AtomStoreView;
import com.radixdlt.tempo.AtomSyncView;
import com.radixdlt.tempo.AtomSynchroniser;
import com.radixdlt.tempo.LegacyUtils;
import com.radixdlt.tempo.TempoAction;
import com.radixdlt.tempo.TempoEpic;
import com.radixdlt.tempo.TempoAtom;
import com.radixdlt.tempo.actions.ReceiveAtomAction;
import com.radixdlt.tempo.actions.ReceiveDeliveryRequestAction;
import com.radixdlt.tempo.actions.ReceiveDeliveryResponseAction;
import com.radixdlt.tempo.actions.ReceiveIterativeRequestAction;
import com.radixdlt.tempo.actions.ReceiveIterativeResponseAction;
import com.radixdlt.tempo.actions.ReceivePushAction;
import com.radixdlt.tempo.actions.RepeatScheduleAction;
import com.radixdlt.tempo.actions.ScheduleAction;
import com.radixdlt.tempo.actions.SendDeliveryRequestAction;
import com.radixdlt.tempo.actions.SendDeliveryResponseAction;
import com.radixdlt.tempo.actions.SendIterativeRequestAction;
import com.radixdlt.tempo.actions.SendIterativeResponseAction;
import com.radixdlt.tempo.actions.SendPushAction;
import com.radixdlt.tempo.actions.AcceptAtomAction;
import com.radixdlt.tempo.epics.ActiveSyncEpic;
import com.radixdlt.tempo.epics.DeliveryEpic;
import com.radixdlt.tempo.epics.IterativeSyncEpic;
import com.radixdlt.tempo.epics.MessagingEpic;
import com.radixdlt.tempo.epics.PassivePeersEpic;
import com.radixdlt.tempo.messages.DeliveryRequestMessage;
import com.radixdlt.tempo.messages.DeliveryResponseMessage;
import com.radixdlt.tempo.messages.IterativeRequestMessage;
import com.radixdlt.tempo.messages.IterativeResponseMessage;
import com.radixdlt.tempo.messages.PushMessage;
import com.radixdlt.tempo.peers.PeerSupplier;
import com.radixdlt.tempo.peers.PeerSupplierAdapter;
import com.radixdlt.tempo.store.IterativeCursorStore;
import org.radix.atoms.Atom;
import org.radix.database.DatabaseEnvironment;
import org.radix.logging.Logger;
import org.radix.logging.Logging;
import org.radix.modules.Modules;
import org.radix.network.messaging.Messaging;
import org.radix.network.peers.PeerHandler;
import org.radix.properties.RuntimeProperties;
import org.radix.universe.system.LocalSystem;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class TempoAtomSynchroniser implements AtomSynchroniser {
	private static final int FULL_INBOUND_QUEUE_RESCHEDULE_TIME_SECONDS = 1;
	private static final int TEMPO_EXECUTOR_POOL_COUNT = 4;

	private final int inboundQueueCapacity;
	private final int syncActionsQueueCapacity;

	private final Logger logger = Logging.getLogger("Sync");

	private final AtomStoreView storeView;
	private final EdgeSelector edgeSelector;
	private final PeerSupplier peerSupplier;

	private final BlockingQueue<TempoAtom> inboundAtoms;
	private final BlockingQueue<TempoAction> tempoActions;
	private final List<TempoEpic> tempoEpics;
	private final ScheduledExecutorService executor;

	private TempoAtomSynchroniser(int inboundQueueCapacity,
	                              int tempoActionsQueueCapacity,
	                              AtomStoreView storeView,
	                              EdgeSelector edgeSelector,
	                              PeerSupplier peerSupplier,
	                              List<TempoEpic> tempoEpics,
	                              List<Function<TempoAtomSynchroniser, TempoEpic>> tempoEpicBuilders) {
		this.inboundQueueCapacity = inboundQueueCapacity;
		this.syncActionsQueueCapacity = tempoActionsQueueCapacity;
		this.storeView = storeView;
		this.edgeSelector = edgeSelector;
		this.peerSupplier = peerSupplier;

		this.executor = Executors.newScheduledThreadPool(TEMPO_EXECUTOR_POOL_COUNT, runnable -> new Thread(null, runnable, "Sync"));
		this.inboundAtoms = new LinkedBlockingQueue<>(this.inboundQueueCapacity);
		this.tempoActions = new LinkedBlockingQueue<>(this.syncActionsQueueCapacity);
		this.tempoEpics = ImmutableList.<TempoEpic>builder()
			.addAll(tempoEpics)
			// TODO get rid of tempoEpicBuilders
			.addAll(tempoEpicBuilders.stream()
				.map(epicBuilder -> epicBuilder.apply(this))
				.collect(Collectors.toList()))
			.add(this::internalEpic)
			.build();

		this.tempoEpics.stream()
			.flatMap(TempoEpic::initialActions)
			.forEach(this::dispatch);
	}

	private void run() {
		while (true) {
			try {
				TempoAction action = tempoActions.take();
				this.executor.execute(() -> this.execute(action));
			} catch (InterruptedException e) {
				// exit if interrupted
				Thread.currentThread().interrupt();
				break;
			}
		}
	}

	private void execute(TempoAction action) {
		if (logger.hasLevel(Logging.TRACE)) {
			logger.trace("Executing " + action.getClass().getSimpleName());
		}

		List<TempoAction> nextActions = tempoEpics.stream()
			.flatMap(epic -> {
				try {
					return epic.epic(action);
				} catch (Exception e) {
					logger.error(String.format("Error while executing %s in %s: '%s'",
						action.getClass().getSimpleName(), epic.getClass().getSimpleName(), e.toString()), e);
					return Stream.empty();
				}
			})
			.collect(Collectors.toList());
		nextActions.forEach(this::dispatch);
	}

	public interface ImmediateDispatcher {
		void dispatch(TempoAction action);
	}

	private void delay(TempoAction action, long delay, TimeUnit unit) {
		// TODO consider cancellation when shutdown/reset
		this.executor.schedule(() -> dispatch(action), delay, unit);
	}

	private void repeatSchedule(TempoAction action, long initialDelay, long recurrentDelay, TimeUnit unit) {
		// TODO consider cancellation when shutdown/reset
		this.executor.scheduleAtFixedRate(() -> dispatch(action), initialDelay, recurrentDelay, unit);
	}

	private void dispatch(TempoAction action) {
		if (!this.tempoActions.add(action)) {
			// TODO handle full action queue better
			throw new IllegalStateException("Action queue full");
		}
	}

	private Stream<TempoAction> internalEpic(TempoAction action) {
		if (action instanceof ReceiveAtomAction) {
			// try to add to inbound queue
			TempoAtom atom = ((ReceiveAtomAction) action).getAtom();
			if (!inboundAtoms.add(atom)) {
				// reschedule
				delay(action, FULL_INBOUND_QUEUE_RESCHEDULE_TIME_SECONDS, TimeUnit.SECONDS);
			}
		} else if (action instanceof ScheduleAction) {
			ScheduleAction schedule = (ScheduleAction) action;
			delay(schedule.getAction(), schedule.getDelay(), schedule.getUnit());
		} else if (action instanceof RepeatScheduleAction) {
			RepeatScheduleAction schedule = (RepeatScheduleAction) action;
			repeatSchedule(schedule.getAction(), schedule.getInitialDelay(), schedule.getRecurrentDelay(), schedule.getUnit());
		}

		return Stream.empty();
	}

	@Override
	public TempoAtom receive() throws InterruptedException {
		return this.inboundAtoms.take();
	}

	@Override
	public void clear() {
		// TODO review whether this is correct
		this.inboundAtoms.clear();
		this.tempoActions.clear();
	}

	@Override
	public List<EUID> selectEdges(TempoAtom atom) {
		return edgeSelector.selectEdges(peerSupplier.getNids(), atom);
	}

	@Override
	public void synchronise(TempoAtom atom) {
		this.dispatch(new AcceptAtomAction(atom));
	}

	@Override
	public AtomSyncView getLegacyAdapter() {
		return new AtomSyncView() {
			@Override
			public void receive(Atom atom) {
				TempoAtom tempoAtom = LegacyUtils.fromLegacyAtom(atom);
				TempoAtomSynchroniser.this.dispatch(new ReceiveAtomAction(tempoAtom));
			}

			@Override
			public AtomStatus getAtomStatus(AID aid) {
				return TempoAtomSynchroniser.this.storeView.contains(aid) ? AtomStatus.STORED : AtomStatus.DOES_NOT_EXIST;
			}

			@Override
			public long getQueueSize() {
				return TempoAtomSynchroniser.this.inboundAtoms.size();
			}

			@Override
			public Map<String, Object> getMetaData() {
				return ImmutableMap.of(
					"inboundQueue", getQueueSize(),
					"inboundQueueCapacity", TempoAtomSynchroniser.this.inboundQueueCapacity,

					"actionQueue", TempoAtomSynchroniser.this.tempoActions.size(),
					"actionQueueCapacity", TempoAtomSynchroniser.this.syncActionsQueueCapacity
				);
			}
		};
	}

	public static Builder builder() {
		return new Builder();
	}

	public static Builder defaultBuilder(AtomStoreView storeView) {
		LocalSystem localSystem = LocalSystem.getInstance();
		Messaging messager = Messaging.getInstance();
		PeerSupplier peerSupplier = new PeerSupplierAdapter(() -> Modules.get(PeerHandler.class));
		Builder builder = new Builder()
			.storeView(storeView)
			.peerSupplier(peerSupplier)
			.addEpic(DeliveryEpic.builder()
				.storeView(storeView)
				.build())
			.addEpic(PassivePeersEpic.builder()
				.peerSupplier(peerSupplier)
				.build())
			.addEpicBuilder(synchroniser -> MessagingEpic.builder()
				.messager(messager)
				.addInbound("tempo.sync.delivery.request", DeliveryRequestMessage.class, ReceiveDeliveryRequestAction::from)
				.addOutbound(SendDeliveryRequestAction.class, SendDeliveryRequestAction::toMessage, SendDeliveryRequestAction::getPeer)
				.addInbound("tempo.sync.delivery.response", DeliveryResponseMessage.class, ReceiveDeliveryResponseAction::from)
				.addOutbound(SendDeliveryResponseAction.class, SendDeliveryResponseAction::toMessage, SendDeliveryResponseAction::getPeer)
				.addInbound("tempo.sync.iterative.request", IterativeRequestMessage.class, ReceiveIterativeRequestAction::from)
				.addOutbound(SendIterativeRequestAction.class, SendIterativeRequestAction::toMessage, SendIterativeRequestAction::getPeer)
				.addInbound("tempo.sync.iterative.response", IterativeResponseMessage.class, ReceiveIterativeResponseAction::from)
				.addOutbound(SendIterativeResponseAction.class, SendIterativeResponseAction::toMessage, SendIterativeResponseAction::getPeer)
				.addInbound("tempo.sync.push", PushMessage.class, ReceivePushAction::from)
				.addOutbound(SendPushAction.class, SendPushAction::toMessage, SendPushAction::getPeer)
				.build(synchroniser::dispatch));
		if (Modules.get(RuntimeProperties.class).get("tempo2.sync.active", true)) {
			builder.addEpic(ActiveSyncEpic.builder()
				.localSystem(localSystem)
				.peerSupplier(peerSupplier)
				.build());
		}
		if (Modules.get(RuntimeProperties.class).get("tempo2.sync.iterative", true)) {
			IterativeCursorStore cursorStore = new IterativeCursorStore(
				() -> Modules.get(DatabaseEnvironment.class),
				() -> Modules.get(Serialization.class)
			);
			cursorStore.open();
			builder.addEpic(IterativeSyncEpic.builder()
				.shardSpaceSupplier(localSystem::getShards)
				.storeView(storeView)
				.cursorStore(cursorStore)
				.build());
		}

		return builder;
	}

	public static class Builder {
		private int inboundQueueCapacity = 1 << 14;
		private int syncActionsQueueCapacity = 1 << 16;
		private AtomStoreView storeView;
		private PeerSupplier peerSupplier;
		private EdgeSelector edgeSelector;
		private final List<TempoEpic> tempoEpics = new ArrayList<>();
		private final List<Function<TempoAtomSynchroniser, TempoEpic>> syncEpicBuilders = new ArrayList<>();

		private Builder() {
		}

		public Builder inboundQueueCapacity(int capacity) {
			this.inboundQueueCapacity = capacity;
			return this;
		}

		public Builder syncActionQueueCapacity(int capacity) {
			this.syncActionsQueueCapacity = capacity;
			return this;
		}

		public Builder addEpic(TempoEpic epic) {
			Objects.requireNonNull(epic, "epic is required");
			this.tempoEpics.add(epic);
			return this;
		}

		public Builder addEpicBuilder(Function<TempoAtomSynchroniser, TempoEpic> epicBuilder) {
			this.syncEpicBuilders.add(epicBuilder);
			return this;
		}

		public Builder storeView(AtomStoreView storeView) {
			this.storeView = storeView;
			return this;
		}

		public Builder peerSupplier(PeerSupplier peerSupplier) {
			this.peerSupplier = peerSupplier;
			return this;
		}

		public Builder edgeSelector(EdgeSelector edgeSelector) {
			this.edgeSelector = edgeSelector;
			return this;
		}

		public TempoAtomSynchroniser build() {
			Objects.requireNonNull(storeView, "storeView is required");
			Objects.requireNonNull(peerSupplier, "peerSupplier is required");
			Objects.requireNonNull(edgeSelector, "edgeSelector is required");

			TempoAtomSynchroniser tempoAtomSynchroniser = new TempoAtomSynchroniser(
				inboundQueueCapacity,
				syncActionsQueueCapacity,
				storeView,
				edgeSelector,
				peerSupplier,
				tempoEpics,
				syncEpicBuilders
			);

			Thread syncDaemon = new Thread(tempoAtomSynchroniser::run);
			syncDaemon.setName("Sync Daemon");
			syncDaemon.setDaemon(true);
			syncDaemon.start();

			return tempoAtomSynchroniser;
		}
	}
}
