/*
 * (C) Copyright 2021 Radix DLT Ltd
 *
 * Radix DLT Ltd licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License.  You may obtain a copy of the
 * License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the specific
 * language governing permissions and limitations under the License.
 */

package com.radixdlt.client.store.berkeley;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.radixdlt.atom.Atom;
import com.radixdlt.atom.SubstateId;
import com.radixdlt.atom.Txn;
import com.radixdlt.atommodel.tokens.StakedTokensParticle;
import com.radixdlt.atommodel.tokens.TokenDefinitionSubstate;
import com.radixdlt.atommodel.tokens.TransferrableTokensParticle;
import com.radixdlt.atommodel.tokens.UnallocatedTokensParticle;
import com.radixdlt.client.ClientApiUtils;
import com.radixdlt.client.store.ClientApiStore;
import com.radixdlt.client.store.ClientApiStoreException;
import com.radixdlt.client.store.ParsedTx;
import com.radixdlt.client.store.ParticleWithSpin;
import com.radixdlt.client.store.TokenBalance;
import com.radixdlt.client.store.TokenDefinitionRecord;
import com.radixdlt.client.store.TransactionParser;
import com.radixdlt.client.store.TxHistoryEntry;
import com.radixdlt.constraintmachine.Particle;
import com.radixdlt.constraintmachine.REInstruction;
import com.radixdlt.constraintmachine.Spin;
import com.radixdlt.counters.SystemCounters;
import com.radixdlt.counters.SystemCounters.CounterType;
import com.radixdlt.crypto.ECPublicKey;
import com.radixdlt.environment.EventProcessor;
import com.radixdlt.environment.ScheduledEventDispatcher;
import com.radixdlt.identifiers.AID;
import com.radixdlt.identifiers.RRI;
import com.radixdlt.identifiers.RadixAddress;
import com.radixdlt.serialization.Serialization;
import com.radixdlt.statecomputer.AtomsCommittedToLedger;
import com.radixdlt.store.DatabaseEnvironment;
import com.radixdlt.store.berkeley.BerkeleyLedgerEntryStore;
import com.radixdlt.utils.Ints;
import com.radixdlt.utils.UInt256;
import com.radixdlt.utils.functional.Failure;
import com.radixdlt.utils.functional.Result;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.OperationStatus;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

import static com.google.common.primitives.UnsignedBytes.lexicographicalComparator;
import static com.radixdlt.constraintmachine.ConstraintMachine.toInstructions;
import static com.radixdlt.counters.SystemCounters.CounterType.COUNT_APIDB_BALANCE_BYTES_READ;
import static com.radixdlt.counters.SystemCounters.CounterType.COUNT_APIDB_BALANCE_BYTES_WRITE;
import static com.radixdlt.counters.SystemCounters.CounterType.COUNT_APIDB_BALANCE_READ;
import static com.radixdlt.counters.SystemCounters.CounterType.COUNT_APIDB_BALANCE_TOTAL;
import static com.radixdlt.counters.SystemCounters.CounterType.COUNT_APIDB_BALANCE_WRITE;
import static com.radixdlt.counters.SystemCounters.CounterType.COUNT_APIDB_FLUSH_COUNT;
import static com.radixdlt.counters.SystemCounters.CounterType.COUNT_APIDB_QUEUE_SIZE;
import static com.radixdlt.counters.SystemCounters.CounterType.COUNT_APIDB_TOKEN_BYTES_READ;
import static com.radixdlt.counters.SystemCounters.CounterType.COUNT_APIDB_TOKEN_BYTES_WRITE;
import static com.radixdlt.counters.SystemCounters.CounterType.COUNT_APIDB_TOKEN_READ;
import static com.radixdlt.counters.SystemCounters.CounterType.COUNT_APIDB_TOKEN_TOTAL;
import static com.radixdlt.counters.SystemCounters.CounterType.COUNT_APIDB_TOKEN_WRITE;
import static com.radixdlt.counters.SystemCounters.CounterType.COUNT_APIDB_TRANSACTION_BYTES_READ;
import static com.radixdlt.counters.SystemCounters.CounterType.COUNT_APIDB_TRANSACTION_BYTES_WRITE;
import static com.radixdlt.counters.SystemCounters.CounterType.COUNT_APIDB_TRANSACTION_READ;
import static com.radixdlt.counters.SystemCounters.CounterType.COUNT_APIDB_TRANSACTION_TOTAL;
import static com.radixdlt.counters.SystemCounters.CounterType.COUNT_APIDB_TRANSACTION_WRITE;
import static com.radixdlt.counters.SystemCounters.CounterType.ELAPSED_APIDB_BALANCE_READ;
import static com.radixdlt.counters.SystemCounters.CounterType.ELAPSED_APIDB_BALANCE_WRITE;
import static com.radixdlt.counters.SystemCounters.CounterType.ELAPSED_APIDB_FLUSH_TIME;
import static com.radixdlt.counters.SystemCounters.CounterType.ELAPSED_APIDB_TOKEN_READ;
import static com.radixdlt.counters.SystemCounters.CounterType.ELAPSED_APIDB_TOKEN_WRITE;
import static com.radixdlt.counters.SystemCounters.CounterType.ELAPSED_APIDB_TRANSACTION_READ;
import static com.radixdlt.counters.SystemCounters.CounterType.ELAPSED_APIDB_TRANSACTION_WRITE;
import static com.radixdlt.serialization.DsonOutput.Output;
import static com.radixdlt.serialization.SerializationUtils.restore;

public class BerkeleyClientApiStore implements ClientApiStore {
	private static final Logger log = LogManager.getLogger();

	private static final String EXECUTED_TRANSACTIONS_DB = "radix.executed_transactions_db";
	private static final String ADDRESS_BALANCE_DB = "radix.address.balance_db";
	private static final String SUPPLY_BALANCE_DB = "radix.supply.balance_db";
	private static final String TOKEN_DEFINITION_DB = "radix.token_definition_db";
	private static final long DEFAULT_FLUSH_INTERVAL = 100L;
	private static final int KEY_BUFFER_INITIAL_CAPACITY = 1024;
	private static final int TIMESTAMP_SIZE = Long.BYTES + Integer.BYTES;

	private final DatabaseEnvironment dbEnv;
	private final BerkeleyLedgerEntryStore store;
	private final Serialization serialization;
	private final SystemCounters systemCounters;
	private final ScheduledEventDispatcher<ScheduledQueueFlush> scheduledFlushEventDispatcher;
	private final StackingCollector<Txn> txCollector = StackingCollector.create();
	private final Observable<AtomsCommittedToLedger> ledgerCommitted;
	private final AtomicLong inputCounter = new AtomicLong();
	private final CompositeDisposable disposable = new CompositeDisposable();
	private final byte universeMagic;

	private Database transactionHistory;
	private Database tokenDefinitions;
	private Database addressBalances;
	private Database supplyBalances;
	private TransactionParser transactionParser;

	@Inject
	public BerkeleyClientApiStore(
		DatabaseEnvironment dbEnv,
		BerkeleyLedgerEntryStore store,
		Serialization serialization,
		SystemCounters systemCounters,
		ScheduledEventDispatcher<ScheduledQueueFlush> scheduledFlushEventDispatcher,
		Observable<AtomsCommittedToLedger> ledgerCommitted,
		@Named("magic") int universeMagic,
		TransactionParser transactionParser
	) {
		this.dbEnv = dbEnv;
		this.store = store;
		this.serialization = serialization;
		this.systemCounters = systemCounters;
		this.scheduledFlushEventDispatcher = scheduledFlushEventDispatcher;
		this.ledgerCommitted = ledgerCommitted;
		this.universeMagic = (byte) (universeMagic & 0xFF);
		this.transactionParser = transactionParser;

		open();
	}

	@Override
	public Result<List<TokenBalance>> getTokenBalances(RadixAddress address) {
		try (var cursor = addressBalances.openCursor(null, null)) {
			var key = asKey(address);
			var data = entry();
			var status = readBalance(() -> cursor.getSearchKeyRange(key, data, null), data);

			if (status != OperationStatus.SUCCESS) {
				return Result.ok(List.of());
			}

			var list = new ArrayList<TokenBalance>();

			do {
				restore(serialization, data.getData(), BalanceEntry.class)
					.onFailureDo(
						() -> log.error("Error deserializing existing balance while scanning DB for address {}", address)
					)
					.toOptional()
					.filter(Predicate.not(BalanceEntry::isStake))
					.filter(entry -> entry.getOwner().equals(address))
					.map(TokenBalance::from)
					.ifPresent(list::add);

				status = readBalance(() -> cursor.getNext(key, data, null), data);
			}
			while (status == OperationStatus.SUCCESS);

			return Result.ok(list);
		}
	}

	@Override
	public void storeCollected() {
		synchronized (txCollector) {
			log.debug("Storing collected transactions started");

			var count = withTime(
				() -> txCollector.consumeCollected(this::storeTransaction),
				() -> systemCounters.increment(COUNT_APIDB_FLUSH_COUNT),
				ELAPSED_APIDB_FLUSH_TIME
			);

			inputCounter.addAndGet(-count);

			log.debug("Storing collected transactions finished. {} transactions processed", count);
		}
	}

	@Override
	public Result<UInt256> getTokenSupply(RRI rri) {
		try (var cursor = supplyBalances.openCursor(null, null)) {
			var key = asKey(rri);
			var data = entry();

			var status = readBalance(() -> cursor.getSearchKeyRange(key, data, null), data);

			if (status != OperationStatus.SUCCESS) {
				return Result.fail("Unknown RRI " + rri.toString());
			}

			return restore(serialization, data.getData(), BalanceEntry.class)
				.onSuccess(entry -> log.debug("Stored token supply balance: {}", entry))
				.map(BalanceEntry::getAmount)
				.map(UInt256.MAX_VALUE::subtract);
		}
	}

	@Override
	public Result<TokenDefinitionRecord> getTokenDefinition(RRI rri) {
		try (var cursor = tokenDefinitions.openCursor(null, null)) {
			var key = asKey(rri);
			var data = entry();

			var status = withTime(
				() -> cursor.getSearchKeyRange(key, data, null),
				() -> addTokenReadBytes(data),
				ELAPSED_APIDB_TOKEN_READ
			);

			if (status != OperationStatus.SUCCESS) {
				return Result.fail("Unknown RRI " + rri.toString());
			}

			return restore(serialization, data.getData(), TokenDefinitionRecord.class);
		}
	}

	@Override
	public Result<List<TxHistoryEntry>> getTransactionHistory(RadixAddress address, int size, Optional<Instant> ptr) {
		if (size <= 0) {
			return Result.fail("Invalid size specified: {0}", size);
		}

		Instant instant = ptr.orElse(Instant.EPOCH);
		var key = asKey(address, instant);
		var data = entry();

		try (var cursor = transactionHistory.openCursor(null, null)) {
			var status = readTxHistory(() -> cursor.getSearchKeyRange(key, data, null), data);

			if (status != OperationStatus.SUCCESS) {
				return Result.ok(List.of());
			}

			// skip first entry if it's the same as the cursor
			if (instantFromKey(key).equals(instant)) {
				status = readTxHistory(() -> cursor.getNext(key, data, null), data);

				if (status != OperationStatus.SUCCESS) {
					return Result.ok(List.of());
				}
			}

			var list = new ArrayList<TxHistoryEntry>();
			var count = new AtomicInteger(0);

			do {
				AID.fromBytes(data.getData())
					.flatMap(this::retrieveTx)
					.onFailureDo(
						() -> log.error("Error deserializing TxID while scanning DB for address {}", address)
					)
					.flatMap(txn -> ClientApiUtils.toParsedTx(txn, universeMagic, sid -> store.loadUpParticle(null, sid)))
					.flatMap(parsed -> transactionParser.parse(address, parsed, instantFromKey(key)))
					.onSuccess(txHistoryEntry -> {
						count.incrementAndGet();
						list.add(txHistoryEntry);
					});

				status = readTxHistory(() -> cursor.getNext(key, data, null), data);
			}
			while (status == OperationStatus.SUCCESS && count.get() < size);

			return Result.ok(list);
		}
	}

	@Override
	public EventProcessor<ScheduledQueueFlush> queueFlushProcessor() {
		return flush -> {
			storeCollected();
			scheduledFlushEventDispatcher.dispatch(ScheduledQueueFlush.create(), DEFAULT_FLUSH_INTERVAL);
		};
	}

	public void close() {
		disposable.dispose();
		storeCollected();

		safeClose(transactionHistory);
		safeClose(tokenDefinitions);
		safeClose(addressBalances);
		safeClose(supplyBalances);
	}

	private Instant instantFromKey(DatabaseEntry key) {
		var buf = Unpooled.wrappedBuffer(key.getData(), key.getSize() - TIMESTAMP_SIZE, TIMESTAMP_SIZE);
		return Instant.ofEpochSecond(buf.readLong(), buf.readInt());
	}

	private Result<Txn> retrieveTx(AID id) {
		return store.get(id)
			.map(Result::ok)
			.orElseGet(() -> Result.fail("Unable to retrieve transaction by ID {0} ", id));
	}

	private <T> T readBalance(Supplier<T> supplier, DatabaseEntry data) {
		return withTime(supplier, () -> addBalanceReadBytes(data), ELAPSED_APIDB_BALANCE_READ);
	}

	private <T> T writeBalance(Supplier<T> supplier, DatabaseEntry data) {
		return withTime(supplier, () -> addBalanceWriteBytes(data), ELAPSED_APIDB_BALANCE_WRITE);
	}

	private <T> T readTxHistory(Supplier<T> supplier, DatabaseEntry data) {
		return withTime(supplier, () -> addTxHistoryReadBytes(data), ELAPSED_APIDB_TRANSACTION_READ);
	}


	private void addBalanceReadBytes(DatabaseEntry data) {
		systemCounters.add(COUNT_APIDB_BALANCE_BYTES_READ, data.getSize());
		systemCounters.increment(COUNT_APIDB_BALANCE_READ);
		systemCounters.increment(COUNT_APIDB_BALANCE_TOTAL);
	}

	private void addBalanceWriteBytes(DatabaseEntry data) {
		systemCounters.add(COUNT_APIDB_BALANCE_BYTES_WRITE, data.getSize());
		systemCounters.increment(COUNT_APIDB_BALANCE_WRITE);
		systemCounters.increment(COUNT_APIDB_BALANCE_TOTAL);
	}

	private void addTokenReadBytes(DatabaseEntry data) {
		systemCounters.add(COUNT_APIDB_TOKEN_BYTES_READ, data.getSize());
		systemCounters.increment(COUNT_APIDB_TOKEN_READ);
		systemCounters.increment(COUNT_APIDB_TOKEN_TOTAL);
	}

	private void addTokenWriteBytes(DatabaseEntry data) {
		systemCounters.add(COUNT_APIDB_TOKEN_BYTES_WRITE, data.getSize());
		systemCounters.increment(COUNT_APIDB_TOKEN_WRITE);
		systemCounters.increment(COUNT_APIDB_TOKEN_TOTAL);
	}

	private void addTxHistoryReadBytes(DatabaseEntry data) {
		systemCounters.add(COUNT_APIDB_TRANSACTION_BYTES_READ, data.getSize());
		systemCounters.increment(COUNT_APIDB_TRANSACTION_READ);
		systemCounters.increment(COUNT_APIDB_TRANSACTION_TOTAL);
	}

	private void addTxHistoryWriteBytes(DatabaseEntry data) {
		systemCounters.add(COUNT_APIDB_TRANSACTION_BYTES_WRITE, data.getSize());
		systemCounters.increment(COUNT_APIDB_TRANSACTION_WRITE);
		systemCounters.increment(COUNT_APIDB_TRANSACTION_TOTAL);
	}

	private <T> T withTime(Supplier<T> supplier, Runnable postAction, CounterType elapsedCounter) {
		final var start = System.nanoTime();
		try {
			return supplier.get();
		} finally {
			final var elapsed = (System.nanoTime() - start + 500L) / 1000L;
			this.systemCounters.add(elapsedCounter, elapsed);
			postAction.run();
		}
	}

	private void open() {
		try {
			// This SuppressWarnings here is valid, as ownership of the underlying
			// resource is not changed here, the resource is just accessed.
			@SuppressWarnings("resource")
			var env = dbEnv.getEnvironment();
			var uniqueConfig = createUniqueConfig();

			addressBalances = env.openDatabase(null, ADDRESS_BALANCE_DB, uniqueConfig);
			supplyBalances = env.openDatabase(null, SUPPLY_BALANCE_DB, uniqueConfig);
			tokenDefinitions = env.openDatabase(null, TOKEN_DEFINITION_DB, uniqueConfig);
			transactionHistory = env.openDatabase(null, EXECUTED_TRANSACTIONS_DB, uniqueConfig);

			if (System.getProperty("db.check_integrity", "1").equals("1")) {
				//TODO: Implement recovery, basically should be the same as fresh DB handling
			}

			if (addressBalances.count() == 0) {
				//Fresh DB, rebuild from log
				rebuildDatabase();
			}

			scheduledFlushEventDispatcher.dispatch(ScheduledQueueFlush.create(), DEFAULT_FLUSH_INTERVAL);

			disposable.add(ledgerCommitted
							   .observeOn(Schedulers.io())
							   .subscribe(transactions -> transactions.getTxns().forEach(this::newTransaction)));

		} catch (Exception e) {
			throw new ClientApiStoreException("Error while opening databases", e);
		}
	}

	private DatabaseConfig createUniqueConfig() {
		return new DatabaseConfig()
			.setAllowCreate(true)
			.setTransactional(true)
			.setKeyPrefixing(true)
			.setBtreeComparator(lexicographicalComparator());
	}

	private void reportError(Failure failure) {
		log.error(failure.message());
	}

	private void storeTransaction(Txn txn) {
		restore(serialization, txn.getPayload(), Atom.class)
			.onSuccess(atom -> ClientApiUtils.extractCreator(atom, universeMagic)
				.ifPresent(author -> storeSingleTransaction(author, txn.getId())))
			.map(atom -> toInstructions(atom.getInstructions()))
			.onSuccess(instructions -> instructionsToParticles(instructions)
				.sorted(Comparator.comparingInt(pi -> pi.getSpin().intValue()))
				.forEach(this::storeSingleSubstate))
			.onFailure(this::reportError);
	}

	private Stream<ParticleWithSpin> instructionsToParticles(List<REInstruction> instructions) {
		return instructions.stream()
			.filter(REInstruction::isPush)
			.map(i -> toParticleWithSpin(i, instructions))
			.peek(substate -> substate.onFailure(this::reportError))
			.filter(Result::isSuccess)
			.map(p -> p.fold(this::shouldNeverHappen, v -> v));
	}

	private Result<ParticleWithSpin> toParticleWithSpin(REInstruction instruction, List<REInstruction> raw) {
		if (instruction.getMicroOp() == REInstruction.REOp.UP) {
			return restore(serialization, instruction.getData(), Particle.class)
				.map(particle -> ParticleWithSpin.create(particle, instruction.getNextSpin()));
		} else if (instruction.getMicroOp() == REInstruction.REOp.VDOWN) {
			return restore(serialization, instruction.getData(), Particle.class)
				.map(particle -> ParticleWithSpin.create(particle, instruction.getNextSpin()));

		} else if (instruction.getMicroOp() == REInstruction.REOp.DOWN) {
			var substateId = SubstateId.fromBytes(instruction.getData());

			return Result.fromOptional(
				store.loadUpParticle(null, substateId),
				"Unable to find particle"
			).map(particle -> ParticleWithSpin.create(particle, instruction.getNextSpin()));
		} else if (instruction.getMicroOp() == REInstruction.REOp.LDOWN) {
			var index = Ints.fromByteArray(instruction.getData());

			return restore(serialization, raw.get(index).getData(), Particle.class)
				.map(particle -> ParticleWithSpin.create(particle, instruction.getNextSpin()));
		} else {
			return Result.fail("Unable to reconstruct particle");
		}
	}

	private <T> T shouldNeverHappen(Failure f) {
		log.error("Should never happen {}", f.message());
		return null;
	}


	private void safeClose(Database database) {
		if (database != null) {
			database.close();
		}
	}

	private void rebuildDatabase() {
		log.info("Database rebuilding is started");

		store.forEach(this::storeTransaction);

		log.info("Database rebuilding is finished successfully");
	}

	private void newTransaction(Txn transaction) {
		txCollector.push(transaction);
		systemCounters.set(COUNT_APIDB_QUEUE_SIZE, inputCounter.incrementAndGet());
	}

	private void storeSingleSubstate(ParticleWithSpin particleWithSpin) {
		if (particleWithSpin.getParticle() instanceof TokenDefinitionSubstate) {
			storeTokenDefinition((TokenDefinitionSubstate) particleWithSpin.getParticle());
		} else {
			//Store balance and supply
			if (particleWithSpin.getSpin() == Spin.DOWN) {
				storeSingleDownSubstate(particleWithSpin.getParticle());
			} else {
				storeSingleUpSubstate(particleWithSpin.getParticle());
			}
		}
	}

	private void storeTokenDefinition(TokenDefinitionSubstate substate) {
		TokenDefinitionRecord.from(substate)
			.onSuccess(this::storeTokenDefinition)
			.onFailure(failure -> log.error("Unable to store token definition: {}", failure.message()));
	}

	private void storeTokenDefinition(TokenDefinitionRecord tokenDefinition) {
		var key = asKey(tokenDefinition.rri());
		var value = serializeToEntry(entry(), tokenDefinition);
		var status = withTime(
			() -> tokenDefinitions.putNoOverwrite(null, key, value),
			() -> addTokenWriteBytes(value),
			ELAPSED_APIDB_TOKEN_WRITE
		);

		if (status != OperationStatus.SUCCESS) {
			log.error("Error while storing token definition {}", tokenDefinition.asJson());
		}
	}

	private void storeSingleTransaction(RadixAddress creator, AID id) {
		//Note: since Java 9 the Clock.systemUTC() produces values with real nanosecond resolution.
		var key = asKey(creator, Instant.now());
		var data = entry(id.getBytes());

		var status = withTime(
			() -> transactionHistory.put(null, key, data),
			() -> addTxHistoryWriteBytes(data),
			ELAPSED_APIDB_TRANSACTION_WRITE
		);

		if (status != OperationStatus.SUCCESS) {
			log.error("Error while storing transaction {} for {}", id, creator);
		}
	}

	private void storeSingleUpSubstate(Particle substate) {
		toBalanceEntry(substate, true).ifPresent(balanceEntry -> {
			var key = asKey(balanceEntry);
			var value = serializeToEntry(entry(), balanceEntry);

			mergeBalances(key, value, balanceEntry, true);
		});
	}

	private void storeSingleDownSubstate(Particle substate) {
		toBalanceEntry(substate, false).ifPresent(balanceEntry -> {
			var key = asKey(balanceEntry);

			mergeBalances(key, entry(), balanceEntry.negate(), false);
		});
	}

	private void mergeBalances(DatabaseEntry key, DatabaseEntry value, BalanceEntry balanceEntry, boolean isUp) {
		var database = balanceEntry.isSupply() ? supplyBalances : addressBalances;
		var oldValue = entry();
		var status = readBalance(() -> database.get(null, key, oldValue, null), oldValue);

		if (status == OperationStatus.NOTFOUND) {
			serializeToEntry(value, balanceEntry);
		} else if (status == OperationStatus.SUCCESS) {
			// Merge with existing balance
			restore(serialization, oldValue.getData(), BalanceEntry.class)
				.map(existingBalance -> existingBalance.add(balanceEntry))
				.onSuccess(entry -> serializeToEntry(value, entry))
				.onFailure(this::reportError);
		}

		status = writeBalance(() -> database.put(null, key, value), value);

		if (status != OperationStatus.SUCCESS) {
			log.error("Error while calculating merged balance {}", balanceEntry);
		}
	}

	private DatabaseEntry serializeToEntry(DatabaseEntry value, Object entry) {
		value.setData(serialization.toDson(entry, Output.ALL));
		return value;
	}

	private static DatabaseEntry asKey(BalanceEntry balanceEntry) {
		var address = buffer().writeBytes(balanceEntry.getOwner().toByteArray());
		var buf = address.writeBytes(balanceEntry.getRri().getName().getBytes());

		if (balanceEntry.isStake()) {
			buf.writeBytes(balanceEntry.getDelegate().toByteArray());
		}

		return entry(buf);
	}

	private static DatabaseEntry asKey(RRI rri) {
		return entry(writeRRI(buffer(), rri));
	}

	private static DatabaseEntry asKey(RadixAddress radixAddress) {
		return entry(buffer().writeBytes(radixAddress.toByteArray()));
	}

	private static DatabaseEntry asKey(RadixAddress radixAddress, Instant timestamp) {
		return entry(buffer()
						 .writeBytes(radixAddress.toByteArray())
						 .writeLong(timestamp.getEpochSecond())
						 .writeInt(timestamp.getNano()));
	}

	private static ByteBuf writeRRI(ByteBuf buf, RRI rri) {
		return buf
			.writeBytes(rri.getAddress().toByteArray())
			.writeBytes(rri.getName().getBytes());
	}

	private static ByteBuf buffer() {
		return Unpooled.buffer(KEY_BUFFER_INITIAL_CAPACITY);
	}

	private static DatabaseEntry entry(byte[] data) {
		return new DatabaseEntry(data);
	}

	private static DatabaseEntry entry(ByteBuf buf) {
		return new DatabaseEntry(buf.array(), 0, buf.readableBytes());
	}

	private static DatabaseEntry entry() {
		return new DatabaseEntry();
	}

	private Optional<BalanceEntry> toBalanceEntry(Particle substate, boolean isUp) {
		if (substate instanceof StakedTokensParticle) {
			var a = (StakedTokensParticle) substate;
			return Optional.of(BalanceEntry.createBalance(
				a.getAddress(),
				a.getDelegateAddress(),
				a.getTokDefRef(),
				a.getGranularity(),
				a.getAmount()
			));
		} else if (substate instanceof TransferrableTokensParticle) {
			var a = (TransferrableTokensParticle) substate;
			return Optional.of(BalanceEntry.createBalance(
				a.getAddress(),
				null,
				a.getTokDefRef(),
				a.getGranularity(),
				a.getAmount()
			));
		} else if (substate instanceof UnallocatedTokensParticle) {
			var a = (UnallocatedTokensParticle) substate;
			return Optional.of(BalanceEntry.createSupply(
				a.getAddress(),
				null,
				a.getTokDefRef(),
				a.getGranularity(),
				a.getAmount()
			));
		}

		return Optional.empty();
	}
}
