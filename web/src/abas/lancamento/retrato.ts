import type {
  Envelope,
  Lancamento,
  LancamentoMudouDeEstado,
  SituacaoDosLancamentos,
} from '../../api/tipos';
import { ATIVOS } from './palavras';

/** Aplica um LancamentoMudouDeEstado no retrato, para a etapa mudar na hora (os números vêm do GET logo depois). */
export function aplicarEvento(
  s: SituacaoDosLancamentos,
  envelope: Envelope<LancamentoMudouDeEstado>,
): SituacaoDosLancamentos {
  const d = envelope.dados;
  const ativo = ATIVOS.includes(d.estado);
  const mudar = (l: Lancamento): Lancamento => ({
    ...l,
    estado: d.estado,
    motivo: d.motivo,
    edicaoId: d.edicaoId ?? l.edicaoId,
    robos: d.robos,
    soltos: d.soltos,
    posicaoNaFila: d.estado === 'NA_FILA' ? l.posicaoNaFila : null,
  });
  if (s.atual?.id === envelope.pedidoId) return { ...s, atual: mudar(s.atual) };
  const daFila = s.fila.find((l) => l.id === envelope.pedidoId);
  if (daFila && ativo)
    return {
      ...s,
      atual: {
        ...mudar(daFila),
        comecouEm: daFila.comecouEm ?? envelope.ocorridoEm,
      },
      fila: s.fila
        .filter((l) => l !== daFila)
        .map((l) => ({
          ...l,
          posicaoNaFila: l.posicaoNaFila && l.posicaoNaFila - 1,
        })),
    };
  return s;
}
