/*
 * (C) Copyright 2021 Radix DLT Ltd
 *
 * Radix DLT Ltd licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 */

package com.radixdlt.api.controller;

import com.radixdlt.api.AbstractJsonRpcController;
import com.radixdlt.api.server.JsonRpcServer;

public final class SystemController extends AbstractJsonRpcController {
	public SystemController(JsonRpcServer jsonRpcServer) {
		super(jsonRpcServer);
	}

	@Override
	public String root() {
		return "/system";
	}
}
/*
private void respondWithLivePeers(final HttpServerExchange exchange) {
		var peerArray = new JSONArray();
		this.peersView.peers()
			.map(peer -> {
				final var peerJson = jsonObject().put("address", NodeAddress.of(peer.getNodeId().getPublicKey()));
				final var channelsJson = jsonArray();
				peer.getChannels().forEach(channel -> {
					final var channelJson = jsonObject();
					channelJson.put("type", channel.isOutbound() ? "out" : "in");
					channelJson.put("localPort", channel.getSocketAddress().getPort());
					channel.getUri().ifPresent(uri -> channelJson.put("uri", uri.toString()));
					channelsJson.put(channelJson);
				});
				peerJson.put("channels", channelsJson);
				return peerJson;
			})
			.forEach(peerArray::put);

		respond(exchange, peerArray);
	}
 */