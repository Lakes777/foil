package io.github.lakes777.foil.pedidos;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** Relógio que o teste avança na mão, para vencer prazos sem esperar 5 minutos. */
class RelogioDeTeste extends Clock {

	private final AtomicReference<Instant> agora = new AtomicReference<>(Instant.now());

	void avancar(Duration quanto) {
		agora.updateAndGet(a -> a.plus(quanto));
	}

	@Override
	public Instant instant() {
		return agora.get();
	}

	@Override
	public ZoneId getZone() {
		return ZoneOffset.UTC;
	}

	@Override
	public Clock withZone(ZoneId zona) {
		return this;
	}

}
