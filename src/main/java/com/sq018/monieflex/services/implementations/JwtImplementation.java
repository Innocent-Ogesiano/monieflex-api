package com.sq018.monieflex.services.implementations;

import com.sq018.monieflex.services.JwtService;
import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.Key;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Map;
import java.util.function.Function;

@Service
public class JwtImplementation implements JwtService {
    @Value("${monieFlex.security.jwt-secret-key}")
    private String JWT_SECRET_KEY;

    protected Key signingKey() {
        return Keys.hmacShaKeyFor(Decoders.BASE64.decode(JWT_SECRET_KEY));
    }

    protected Claims getAllClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(signingKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    protected <T> T extractClaims(String token, Function<Claims, T> extract) {
        return extract.apply(getAllClaims(token));
    }

    @Override
    public String extractEmailAddressFromToken(String token) {
        return extractClaims(token, Claims::getSubject);
    }

    @Override
    public String generateJwtToken(Map<String, Object> claims, String emailAddress, Long expiryDate) {
        Instant issuedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant expiration = issuedAt.plus(expiryDate == null ? 86400000 : expiryDate, ChronoUnit.MILLIS);

        return Jwts.builder()
                .setClaims(claims)
                .setSubject(emailAddress)
                .signWith(signingKey())
                .setIssuedAt(Date.from(issuedAt))
                .setExpiration(Date.from(expiration))
                .compact();
    }

    @Override
    public Boolean isExpired(String token) {
        return extractClaims(token, Claims::getExpiration).before(new Date(System.currentTimeMillis()));
    }
}