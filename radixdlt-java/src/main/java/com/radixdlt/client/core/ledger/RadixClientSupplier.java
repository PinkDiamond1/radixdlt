package com.radixdlt.client.core.ledger;

import com.radixdlt.client.core.address.RadixUniverseConfig;
import com.radixdlt.client.core.ledger.selector.CompatibleApiVersionFilter;
import com.radixdlt.client.core.ledger.selector.ConnectionAliveFilter;
import com.radixdlt.client.core.ledger.selector.RadixPeerFilter;
import com.radixdlt.client.core.ledger.selector.RadixPeerSelector;
import com.radixdlt.client.core.ledger.selector.RandomSelector;
import com.radixdlt.client.core.ledger.selector.ShardFilter;
import com.radixdlt.client.core.ledger.selector.MatchingUniverseFilter;
import com.radixdlt.client.core.network.RadixJsonRpcClient;
import com.radixdlt.client.core.network.RadixNetwork;
import com.radixdlt.client.core.network.RadixNetworkState;
import com.radixdlt.client.core.network.RadixPeer;
import io.reactivex.Observable;
import io.reactivex.Single;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Given a network, a selector and filters, yields the {@link RadixJsonRpcClient} to use
 */
public class RadixClientSupplier {
	private static final Logger LOGGER = LoggerFactory.getLogger(RadixClientSupplier.class);

	/**
	 * The amount of time to delay in between node connection requests
	 */
	private final int delaySecs;

	/**
	 * The network of peers available to connect to
	 */
	private final RadixNetwork network;

	/**
	 * The selector to use to decide between a list of viable peers
	 */
	private final RadixPeerSelector selector;

	/**
	 * The filters to test whether a peer is desirable
	 */
	private final List<RadixPeerFilter> filters;

	/**
	 * Create a client selector from a certain network with the default filters and the a randomized selector
	 * @param network The network
	 */
	public RadixClientSupplier(RadixNetwork network) {
		this(network, new RandomSelector(), Arrays.asList(
				new ConnectionAliveFilter(),
				new CompatibleApiVersionFilter(RadixJsonRpcClient.API_VERSION)));
	}

	/**
	 * Create a client selector from a certain network with a matching universe filter, the default filters and a randomized selector
	 * @param network The network
	 * @param config The universe config for the matching universe filter
	 */
	public RadixClientSupplier(RadixNetwork network, RadixUniverseConfig config) {
		this(network, new RandomSelector(), Arrays.asList(
				new ConnectionAliveFilter(),
				new CompatibleApiVersionFilter(RadixJsonRpcClient.API_VERSION),
				new MatchingUniverseFilter(config)));
	}

	/**
	 * Create a client selector from a certain network with a certain selector and filters
	 * @param network The network
	 * @param selector The selector used to select a peer from the desirable peer list
	 * @param filters The filters used to test the desirability of peers
	 */
	public RadixClientSupplier(RadixNetwork network, RadixPeerSelector selector, RadixPeerFilter... filters) {
		this(network, selector, Arrays.asList(filters));
	}

	/**
	 * Create a client selector from a certain network with a certain selector and filters
	 * @param network The network
	 * @param selector The selector used to select a peer from the desirable peer list
	 * @param filters The filters used to test the desirability of peers
	 */
	public RadixClientSupplier(RadixNetwork network, RadixPeerSelector selector, List<RadixPeerFilter> filters) {
		Objects.requireNonNull(network, "network is required");
		Objects.requireNonNull(selector, "selector is required");
		Objects.requireNonNull(filters, "filters is required");

		this.network = network;
		this.selector = selector;
		this.filters = filters;
		this.delaySecs = 3;
	}

	/**
	 * Returns a cold single of the first peer found which supports
	 * a set short shards which intersects with a given shard
	 *
	 * @param shard a shards to find an intersection with
	 * @return a cold single of the first matching Radix client
	 */
	public Single<RadixJsonRpcClient> getRadixClient(Long shard) {
		return getRadixClient(Collections.singleton(shard));
	}

	/**
	 * Returns a cold single of the first peer found which supports
	 * a set short shards which intersects with a given set of shards.
	 *
	 * @param shards set of shards to find an intersection with
	 * @return a cold single of the first matching Radix client
	 */
	public Single<RadixJsonRpcClient> getRadixClient(Set<Long> shards) {
		if (shards.isEmpty()) {
			throw new IllegalArgumentException("Shards cannot be empty to obtain a radixClient.");
		}

		List<RadixPeerFilter> expandedFilters = new ArrayList<>(this.filters);
		expandedFilters.add(new ShardFilter(shards));

		return getRadixClients(this.selector, expandedFilters).firstOrError();
	}

	/**
	 * Returns a cold observable of viable peers found according
	 * to the selector and filters configured.
	 * @return A cold observable of clients
	 */
	public Observable<RadixJsonRpcClient> getRadixClients() {
		return getRadixClients(this.selector, this.filters);
	}

	/**
	 * Returns a cold observable of the first peer found which supports
	 * a set short shards which intersects with a given set of shards.
	 *
	 * @param shards set of shards to find an intersection with
	 * @return a cold observable of the first matching Radix client
	 */
	public Observable<RadixJsonRpcClient> getRadixClients(Set<Long> shards) {
		if (shards.isEmpty()) {
			throw new IllegalArgumentException("Shards cannot be empty to obtain a radixClient.");
		}

		List<RadixPeerFilter> expandedFilters = new ArrayList<>(this.filters);
		expandedFilters.add(new ShardFilter(shards));

		return getRadixClients(this.selector, expandedFilters);
	}

	private Observable<RadixJsonRpcClient> getRadixClients(RadixPeerSelector selector, List<RadixPeerFilter> filters) {
		return this.network.getNetworkState()
				.map(state -> collectDesirablePeers(filters, state))
				.filter(viablePeerList -> !viablePeerList.isEmpty())
				.map(selector::apply)
				.map(RadixPeer::getRadixClient)
				.zipWith(Observable.interval(delaySecs, TimeUnit.SECONDS), (c, t) -> c);
	}

	private List<RadixPeer> collectDesirablePeers(List<RadixPeerFilter> filters, RadixNetworkState state) {
		return state.peers.entrySet().stream()
				.filter(entry -> filters.stream()
						.allMatch(filter -> filter.test(entry.getValue())))
				.map(Map.Entry::getKey)
				.collect(Collectors.toList());
	}
}
