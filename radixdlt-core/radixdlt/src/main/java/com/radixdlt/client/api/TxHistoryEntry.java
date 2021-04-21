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

package com.radixdlt.client.api;

import org.json.JSONObject;

import com.radixdlt.client.store.ActionEntry;
import com.radixdlt.client.store.MessageEntry;
import com.radixdlt.identifiers.AID;
import com.radixdlt.utils.UInt256;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static org.radix.api.jsonrpc.JsonRpcUtil.fromList;
import static org.radix.api.jsonrpc.JsonRpcUtil.jsonObject;

import static java.util.Objects.requireNonNull;

public class TxHistoryEntry {
	private final AID txId;
	private final Instant timestamp;
	private final UInt256 fee;
	private final MessageEntry message;
	private final List<ActionEntry> actions;

	private TxHistoryEntry(AID txId, Instant timestamp, UInt256 fee, MessageEntry message, List<ActionEntry> actions) {
		this.txId = txId;
		this.timestamp = timestamp;
		this.fee = fee;
		this.message = message;
		this.actions = actions;
	}

	public static TxHistoryEntry create(AID txId, Instant date, UInt256 fee, MessageEntry message, List<ActionEntry> actions) {
		requireNonNull(txId);
		requireNonNull(date);
		requireNonNull(fee);
		requireNonNull(actions);
		return new TxHistoryEntry(txId, date, fee, message, actions);
	}

	public Instant timestamp() {
		return timestamp;
	}

	public AID getTxId() {
		return txId;
	}

	public UInt256 getFee() {
		return fee;
	}

	public MessageEntry getMessage() {
		return message;
	}

	public List<ActionEntry> getActions() {
		return actions;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}

		if (!(o instanceof TxHistoryEntry)) {
			return false;
		}

		var that = (TxHistoryEntry) o;
		return txId.equals(that.txId)
			&& timestamp.equals(that.timestamp)
			&& fee.equals(that.fee)
			&& Objects.equals(message, that.message)
			&& actions.equals(that.actions);
	}

	@Override
	public int hashCode() {
		return Objects.hash(txId, timestamp, fee, message, actions);
	}

	public JSONObject asJson(byte magic) {
		return jsonObject()
			.put("txID", txId)
			.put("sentAt", DateTimeFormatter.ISO_INSTANT.format(timestamp))
			.put("fee", fee)
			.put("actions", fromList(actions, entry -> entry.asJson(magic)))
			.putOpt("message", Optional.ofNullable(message).map(MessageEntry::asJson).orElse(null));
	}

	@Override
	public String toString() {
		// zero magic is irrelevant here as this method is not used to build response
		return asJson((byte) 0).toString(2);
	}
}
