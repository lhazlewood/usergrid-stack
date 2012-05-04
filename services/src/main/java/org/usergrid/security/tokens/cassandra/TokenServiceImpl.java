package org.usergrid.security.tokens.cassandra;

import static org.apache.commons.codec.binary.Base64.decodeBase64;
import static org.apache.commons.codec.binary.Base64.encodeBase64URLSafeString;
import static org.apache.commons.codec.digest.DigestUtils.sha;
import static org.usergrid.persistence.cassandra.CassandraPersistenceUtils.getColumnMap;
import static org.usergrid.persistence.cassandra.CassandraService.TOKENS_CF;
import static org.usergrid.security.tokens.TokenType.ACCESS;
import static org.usergrid.security.tokens.TokenType.EMAIL;
import static org.usergrid.security.tokens.TokenType.OFFLINE;
import static org.usergrid.security.tokens.TokenType.REFRESH;
import static org.usergrid.utils.ConversionUtils.bytebuffer;
import static org.usergrid.utils.ConversionUtils.bytes;
import static org.usergrid.utils.ConversionUtils.getLong;
import static org.usergrid.utils.ConversionUtils.string;
import static org.usergrid.utils.ConversionUtils.uuid;
import static org.usergrid.utils.MapUtils.hasKeys;
import static org.usergrid.utils.MapUtils.hashMap;
import static org.usergrid.utils.UUIDUtils.getTimestampInMillis;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import org.mortbay.log.Log;
import org.springframework.beans.factory.annotation.Autowired;
import org.usergrid.persistence.cassandra.CassandraService;
import org.usergrid.security.AuthPrincipalInfo;
import org.usergrid.security.AuthPrincipalType;
import org.usergrid.security.tokens.TokenInfo;
import org.usergrid.security.tokens.TokenService;
import org.usergrid.security.tokens.TokenType;
import org.usergrid.utils.JsonUtils;
import org.usergrid.utils.UUIDUtils;

public class TokenServiceImpl implements TokenService {

	private static final String TOKEN_PRINCIPAL_TYPE = "principal";
	private static final String TOKEN_TYPE_ACCESS = "access";
	private static final String TOKEN_STATE = "state";
	private static final String TOKEN_APPLICATION = "application";
	private static final String TOKEN_ENTITY = "entity";
	private static final String TOKEN_ACCESSED = "accessed";
	private static final String TOKEN_CREATED = "created";
	private static final String TOKEN_TYPE = "type";
	private static final String TOKEN_UUID = "uuid";

	public static final String TOKEN_SECRET_SALT = "super secret token value";

	// Short-lived token is good for 24 hours
	public static final long SHORT_TOKEN_AGE = 24 * 60 * 60 * 1000;

	// Long-lived token is good for 7 days
	public static final long LONG_TOKEN_AGE = 7 * 24 * 60 * 60 * 1000;

	String tokenSecretSalt = TOKEN_SECRET_SALT;

	long maxPersistenceTokenAge = LONG_TOKEN_AGE;

	Map<TokenType, Long> tokenExpirations = hashMap(ACCESS, SHORT_TOKEN_AGE)
			.map(REFRESH, LONG_TOKEN_AGE).map(EMAIL, LONG_TOKEN_AGE)
			.map(OFFLINE, LONG_TOKEN_AGE);

	long maxAccessTokenAge = SHORT_TOKEN_AGE;
	long maxRefreshTokenAge = LONG_TOKEN_AGE;
	long maxEmailTokenAge = LONG_TOKEN_AGE;
	long maxOfflineTokenAge = LONG_TOKEN_AGE;

	protected CassandraService cassandra;

	protected Properties properties;

	public TokenServiceImpl() {

	}

	long getExpirationProperty(String name, long default_expiration) {
		long expires = Long.parseLong(properties.getProperty(
				"usergrid.auth.token." + name + ".expires", ""
						+ default_expiration));
		return expires > 0 ? expires : default_expiration;
	}

	long getExpirationForTokenType(TokenType tokenType) {
		Long l = tokenExpirations.get(tokenType);
		if (l != null) {
			return l;
		}
		return SHORT_TOKEN_AGE;
	}

	void setExpirationFromProperties(String name) {
		TokenType tokenType = TokenType.valueOf(name.toUpperCase());
		long expires = Long.parseLong(properties.getProperty(
				"usergrid.auth.token." + name + ".expires", ""
						+ getExpirationForTokenType(tokenType)));
		if (expires > 0) {
			tokenExpirations.put(tokenType, expires);
		}
		Log.info(name + " token expires after "
				+ getExpirationForTokenType(tokenType) / 1000 + " seconds");
	}

	@Autowired
	public void setProperties(Properties properties) {
		this.properties = properties;

		if (properties != null) {
			maxPersistenceTokenAge = getExpirationProperty("persistence",
					maxPersistenceTokenAge);

			setExpirationFromProperties("access");
			setExpirationFromProperties("refresh");
			setExpirationFromProperties("email");
			setExpirationFromProperties("offline");

			tokenSecretSalt = properties.getProperty(
					"usergrid.auth.token_secret_salt", TOKEN_SECRET_SALT);
		}
	}

	@Autowired
	public void setCassandraService(CassandraService cassandra) {
		this.cassandra = cassandra;
	}

	@Override
	public String createToken(TokenType tokenType, String type,
			Map<String, Object> state) throws Exception {
		return createToken(tokenType, type, null, state);
	}

	@Override
	public String createToken(AuthPrincipalInfo principal) throws Exception {
		return createToken(TokenType.ACCESS, null, principal, null);
	}

	@Override
	public String createToken(AuthPrincipalInfo principal,
			Map<String, Object> state) throws Exception {
		return createToken(TokenType.ACCESS, null, principal, state);
	}

	@Override
	public String createToken(TokenType tokenType, String type,
			AuthPrincipalInfo principal, Map<String, Object> state)
			throws Exception {
		UUID uuid = UUIDUtils.newTimeUUID();
		long timestamp = getTimestampInMillis(uuid);
		if (type == null) {
			type = TOKEN_TYPE_ACCESS;
		}
		TokenInfo tokenInfo = new TokenInfo(uuid, type, timestamp, timestamp,
				principal, state);
		putTokenInfo(tokenInfo);
		return getTokenForUUID(tokenType, uuid);
	}

	@Override
	public void accessTokenInfo(String token) throws Exception {
		UUID uuid = getUUIDForToken(token);
		if (uuid != null) {
			cassandra.setColumn(cassandra.getSystemKeyspace(), TOKENS_CF, uuid,
					TOKEN_ACCESSED, System.currentTimeMillis(),
					(int) (maxPersistenceTokenAge / 1000));
		}
	}

	@Override
	public TokenInfo getTokenInfo(String token) throws Exception {
		TokenInfo tokenInfo = null;
		UUID uuid = getUUIDForToken(token);
		if (uuid != null) {
			tokenInfo = getTokenInfo(uuid);
			if (tokenInfo != null) {
				cassandra.setColumn(cassandra.getSystemKeyspace(), TOKENS_CF,
						uuid, TOKEN_ACCESSED, System.currentTimeMillis(),
						(int) (maxPersistenceTokenAge / 1000));
			}
		}
		return tokenInfo;
	}

	@Override
	public String refreshToken(String token) throws Exception {
		TokenInfo tokenInfo = getTokenInfo(getUUIDForToken(token));
		if (tokenInfo != null) {
			putTokenInfo(tokenInfo);
			return getTokenForUUID(TokenType.ACCESS, tokenInfo.getUuid());
		}
		return null;
	}

	public TokenInfo getTokenInfo(UUID uuid) throws Exception {
		if (uuid == null) {
			return null;
		}
		Map<String, ByteBuffer> columns = getColumnMap(cassandra.getAllColumns(
				cassandra.getSystemKeyspace(), TOKENS_CF, uuid));
		if (!hasKeys(columns, TOKEN_UUID, TOKEN_TYPE, TOKEN_CREATED,
				TOKEN_ACCESSED)) {
			return null;
		}
		String type = string(columns.get(TOKEN_TYPE));
		long created = getLong(columns.get(TOKEN_CREATED));
		long accessed = getLong(columns.get(TOKEN_ACCESSED));
		String principalTypeStr = string(columns.get(TOKEN_PRINCIPAL_TYPE));
		AuthPrincipalType principalType = null;
		if (principalTypeStr != null) {
			try {
				principalType = AuthPrincipalType.valueOf(principalTypeStr
						.toUpperCase());
			} catch (IllegalArgumentException e) {
			}
		}
		AuthPrincipalInfo principal = null;
		if (principalType != null) {
			UUID entityId = uuid(columns.get(TOKEN_ENTITY));
			UUID appId = uuid(columns.get(TOKEN_APPLICATION));
			principal = new AuthPrincipalInfo(principalType, entityId, appId);
		}
		@SuppressWarnings("unchecked")
		Map<String, Object> state = (Map<String, Object>) JsonUtils
				.fromByteBuffer(columns.get(TOKEN_STATE));
		return new TokenInfo(uuid, type, created, accessed, principal, state);
	}

	public void putTokenInfo(TokenInfo tokenInfo) throws Exception {
		Map<String, ByteBuffer> columns = new HashMap<String, ByteBuffer>();
		columns.put(TOKEN_UUID, bytebuffer(tokenInfo.getUuid()));
		columns.put(TOKEN_TYPE, bytebuffer(tokenInfo.getType()));
		columns.put(TOKEN_CREATED, bytebuffer(tokenInfo.getCreated()));
		columns.put(TOKEN_ACCESSED, bytebuffer(tokenInfo.getAccessed()));
		if (tokenInfo.getPrincipal() != null) {
			columns.put(TOKEN_PRINCIPAL_TYPE, bytebuffer(tokenInfo
					.getPrincipal().getType().toString().toLowerCase()));
			columns.put(TOKEN_ENTITY, bytebuffer(tokenInfo.getPrincipal()
					.getUuid()));
			columns.put(TOKEN_APPLICATION, bytebuffer(tokenInfo.getPrincipal()
					.getApplicationId()));
		}
		columns.put(TOKEN_STATE, JsonUtils.toByteBuffer(tokenInfo.getState()));
		cassandra.setColumns(cassandra.getSystemKeyspace(), TOKENS_CF,
				bytes(tokenInfo.getUuid()), columns,
				(int) (maxPersistenceTokenAge / 1000));
	}

	public UUID getUUIDForToken(String token) {
		TokenType tokenType = TokenType.getFromBase64String(token);
		byte[] bytes = decodeBase64(token
				.substring(TokenType.BASE64_PREFIX_LENGTH));
		UUID uuid = uuid(bytes);
		long timestamp = getTimestampInMillis(uuid);
		if ((getExpirationForTokenType(tokenType) > 0)
				&& (System.currentTimeMillis() > (timestamp + getExpirationForTokenType(tokenType)))) {
			return null;
		}
		int i = 16;
		long expires = Long.MAX_VALUE;
		if (tokenType.getExpires()) {
			expires = ByteBuffer.wrap(bytes, i, 8).getLong();
			i = 24;
		}
		ByteBuffer expected = ByteBuffer.allocate(20);
		expected.put(sha(tokenType.getPrefix() + uuid + tokenSecretSalt
				+ expires));
		expected.rewind();
		ByteBuffer signature = ByteBuffer.wrap(bytes, i, 20);
		if (!signature.equals(expected)) {
			return null;
		}
		return uuid;
	}

	public String getTokenForUUID(TokenType tokenType, UUID uuid) {
		int l = 36;
		if (tokenType.getExpires()) {
			l += 8;
		}
		ByteBuffer bytes = ByteBuffer.allocate(l);
		bytes.put(bytes(uuid));
		long expires = Long.MAX_VALUE;
		if (tokenType.getExpires()) {
			expires = UUIDUtils.getTimestampInMillis(uuid)
					+ getExpirationForTokenType(tokenType);
			bytes.putLong(expires);
		}
		bytes.put(sha(tokenType.getPrefix() + uuid + tokenSecretSalt + expires));
		return tokenType.getBase64Prefix()
				+ encodeBase64URLSafeString(bytes.array());
	}

}
