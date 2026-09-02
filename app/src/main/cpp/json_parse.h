// json_parse.h — extração de campos JSON consciente da estrutura.
//
// Histórico: os extratores antigos (jsonExtract*) faziam busca cega por
// substrings ("\"chave\"") em todo o documento. Isso confundia:
//   1. chave escrita DENTRO de um valor string (ex.: texto de chat citando
//      "id": 999 quando o servidor escapa as aspas, o padrao cru deixava de
//      casar, mas nomes de valor iguais a nomes de chave sim casavam);
//   2. colchetes/chaves dentro de strings fechavam arrays/objetos antes da
//      hora (jsonExtractArray/jsonExtractObject antigos nao conheciam string);
//   3. inteiros negativos (jsonExtractInt ignorava o sinal e devolvia 0);
//   4. valores booleanos: jsonExtractString(line, "e2ee") == "true" nunca
//      funcionava com JSON bool — o parser antigo pegava o NOME da chave
//      seguinte. O servidor envia "e2ee":true como bool.
//
// Esta implementação varre o NÍVEL RAIZ do objeto com plena noção de
// estrutura (strings, escapes, aninhamento), localiza o par "chave":valor
// correto e então extrai o valor com um scanner string-aware. Nada dentro
// de strings aninhadas é interpretado como chave.
//
// Composição legada preservada: se o buffer não começa com '{', o scanner
// procura o primeiro '{' FORA de strings e o trata como raiz — os call
// sites históricos fazem line.substr(line.find("\"server\"")) e depois
// extraem campos do subobjeto; isso continua funcionando.
//
// Header-only, sem dependências, thread-safe (funções puras).
#pragma once

#include <cstdint>
#include <string>

inline constexpr size_t kJsonMaxDocBytes = 2 * 1024 * 1024;

// ------------------------------------------------------------------ escapo
// Escapa strings antes de inseri-las em mensagens JSON montadas manualmente.
inline std::string jsonEscape(const std::string& input) {
    std::string out;
    out.reserve(input.size() + 8);
    static const char hex[] = "0123456789abcdef";
    for (unsigned char c : input) {
        switch (c) {
        case '\\': out += "\\\\"; break;
        case '"':  out += "\\\""; break;
        case '\b': out += "\\b"; break;
        case '\f': out += "\\f"; break;
        case '\n': out += "\\n"; break;
        case '\r': out += "\\r"; break;
        case '\t': out += "\\t"; break;
        default:
            if (c < 0x20) {
                out += "\\u00";
                out += hex[(c >> 4) & 0x0f];
                out += hex[c & 0x0f];
            } else {
                out += static_cast<char>(c);
            }
            break;
        }
    }
    return out;
}

// Reverte as sequências JSON mais comuns (\", \\, \n, ...).
inline std::string jsonUnescape(const std::string& input) {
    std::string out;
    out.reserve(input.size());
    for (size_t i = 0; i < input.size(); ++i) {
        if (input[i] != '\\' || i + 1 >= input.size()) {
            out += input[i];
            continue;
        }
        const char c = input[++i];
        switch (c) {
        case '"': out += '"'; break;
        case '\\': out += '\\'; break;
        case '/': out += '/'; break;
        case 'b': out += '\b'; break;
        case 'f': out += '\f'; break;
        case 'n': out += '\n'; break;
        case 'r': out += '\r'; break;
        case 't': out += '\t'; break;
        default:
            out += '\\';
            out += c; // sequência desconhecida: preserva literal
            break;
        }
    }
    return out;
}

// ------------------------------------------------------------------ scanner
namespace hjp {

inline bool isWs(char c) { return c == ' ' || c == '\t' || c == '\r' || c == '\n'; }

inline size_t skipWs(const std::string& s, size_t i) {
    while (i < s.size() && isWs(s[i])) ++i;
    return i;
}

// Varre uma string JSON começando em |i| (deve apontar para a aspa de
// abertura) e devolve o índice logo APÓS a aspa de fechamento real, ou npos
// se truncado. Aspas escapadas (\") não fecham a string.
inline size_t skipString(const std::string& json, size_t i) {
    if (i >= json.size() || json[i] != '"') return std::string::npos;
    ++i;
    bool esc = false;
    while (i < json.size()) {
        const char c = json[i];
        if (esc) { esc = false; ++i; continue; }
        if (c == '\\') { esc = true; ++i; continue; }
        if (c == '"') return i + 1;
        ++i;
    }
    return std::string::npos;
}

// Pula o valor que começa em |i| (string, objeto, array, número ou literal)
// e devolve o índice logo após o seu fim, ou npos se truncado. Colchetes e
// chaves DENTRO de strings não contam para a profundidade — era exatamente
// o defeito do contador antigo do jsonExtractArray.
inline size_t skipValue(const std::string& json, size_t i) {
    if (i >= json.size()) return std::string::npos;
    if (json[i] == '"') return skipString(json, i);
    if (json[i] == '{' || json[i] == '[') {
        const char open = json[i];
        const char close = (open == '{') ? '}' : ']';
        int depth = 0;
        bool inString = false, esc = false;
        for (; i < json.size(); ++i) {
            const char c = json[i];
            if (inString) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') { inString = true; continue; }
            if (c == open) ++depth;
            else if (c == close) {
                --depth;
                if (depth == 0) return i + 1;
            }
        }
        return std::string::npos;
    }
    // Número / true / false / null: consome até delimitador estrutural.
    while (i < json.size() && !isWs(json[i]) && json[i] != ',' &&
           json[i] != '}' && json[i] != ']')
        ++i;
    return i;
}

// Localiza a chave no nível raiz do objeto e devolve o início do valor.
struct FieldPos {
    bool found = false;
    size_t valueStart = 0;
};

inline FieldPos findField(const std::string& json, const std::string& key) {
    FieldPos out;
    if (json.size() > kJsonMaxDocBytes || key.size() > 64) return out;

    // Raiz do documento: primeiro '{' fora de strings. Documentos completos
    // já começam com '{'; fragmentos compostos (substr a partir de "server")
    // levam o prefixo "chave": até o '{' do subobjeto — ambos cobertos.
    size_t i = skipWs(json, 0);
    if (i >= json.size()) return out;
    if (json[i] != '{') {
        bool inString = false, esc = false;
        for (; i < json.size(); ++i) {
            const char c = json[i];
            if (inString) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') { inString = true; continue; }
            if (c == '{') break;
        }
        if (i >= json.size()) return out;
    }
    ++i; // consumiu o '{' da raiz

    // Percorre os pares "chave": valor do nível raiz.
    for (;;) {
        i = skipWs(json, i);
        // Fim do objeto raiz ou lixo: não achou.
        if (i >= json.size() || json[i] == '}') return out;
        if (json[i] == ',') { ++i; continue; }
        if (json[i] != '"') return out; // malformado

        const size_t keyStart = i;
        i = skipString(json, i);
        if (i == std::string::npos) return out;
        const std::string rawKey = json.substr(keyStart, i - keyStart);

        i = skipWs(json, i);
        if (i >= json.size() || json[i] != ':') return out;
        ++i;
        i = skipWs(json, i);
        if (i >= json.size()) return out;

        // Compara a chave com aspas: bruta (servidor nunca escapa chaves) ou
        // desescapada (robustez contra pares exóticos).
        const std::string quoted = "\"" + key + "\"";
        if (rawKey == quoted || jsonUnescape(rawKey) == key) {
            out.found = true;
            out.valueStart = i;
            return out;
        }

        // Não é a chave procurada: pula o valor INTEIRO antes de continuar.
        i = skipValue(json, i);
        if (i == std::string::npos) return out;
    }
}

} // namespace hjp

// ------------------------------------------------------------- API pública
// Mesmas assinaturas dos extratores históricos — os call sites não mudam.

// String: desescapa o valor entre aspas. Para booleanos/números devolve o
// literal ("true"/"false"/"42") — corrige o flag e2ee do chat, que o parser
// antigo lia como o nome da chave seguinte.
inline std::string jsonExtractString(const std::string& json, const std::string& key) {
    const hjp::FieldPos f = hjp::findField(json, key);
    if (!f.found || f.valueStart >= json.size()) return "";
    if (json[f.valueStart] == '"') {
        const size_t end = hjp::skipString(json, f.valueStart);
        if (end == std::string::npos || end < 2) return "";
        // Descarta aspas das pontas; conteúdo bruto ainda está escapado.
        return jsonUnescape(json.substr(f.valueStart + 1, end - f.valueStart - 2));
    }
    // Literal sem aspas (bool/número): devolve o token cru.
    const size_t end = hjp::skipValue(json, f.valueStart);
    if (end == std::string::npos) return "";
    return json.substr(f.valueStart, end - f.valueStart);
}

// Inteiro: aceita sinal negativo (correção do relatório: {"position": -1}
// devolvia 0). Acumula em 64 bits com clamp para não estourar int.
inline int jsonExtractInt(const std::string& json, const std::string& key) {
    const hjp::FieldPos f = hjp::findField(json, key);
    if (!f.found || f.valueStart >= json.size()) return 0;
    size_t i = f.valueStart;
    bool neg = false;
    if (json[i] == '-' || json[i] == '+') {
        neg = (json[i] == '-');
        ++i;
    }
    long long acc = 0;
    bool any = false;
    while (i < json.size() && json[i] >= '0' && json[i] <= '9') {
        acc = acc * 10 + (json[i] - '0');
        if (acc > 2147483647LL) acc = 2147483647LL; // clamp
        any = true;
        ++i;
    }
    if (!any) return 0;
    return static_cast<int>(neg ? -acc : acc);
}

inline uint64_t jsonExtractUint64(const std::string& json, const std::string& key) {
    const hjp::FieldPos f = hjp::findField(json, key);
    if (!f.found || f.valueStart >= json.size()) return 0;
    size_t i = f.valueStart;
    uint64_t acc = 0;
    bool any = false;
    while (i < json.size() && json[i] >= '0' && json[i] <= '9') {
        if (acc > (UINT64_MAX - 9) / 10) return UINT64_MAX; // overflow
        acc = acc * 10 + static_cast<uint64_t>(json[i] - '0');
        any = true;
        ++i;
    }
    return any ? acc : 0;
}

// Array: devolve o texto bruto do array (com colchetes). O scanner
// string-aware impede que ']' dentro de um nome (ex.: "ROTA]") feche cedo.
inline std::string jsonExtractArray(const std::string& json, const std::string& key) {
    const hjp::FieldPos f = hjp::findField(json, key);
    if (!f.found || f.valueStart >= json.size() || json[f.valueStart] != '[')
        return "[]";
    const size_t end = hjp::skipValue(json, f.valueStart);
    if (end == std::string::npos) return "[]";
    return json.substr(f.valueStart, end - f.valueStart);
}

// Objeto: devolve o texto bruto do subobjeto (com chaves).
inline std::string jsonExtractObject(const std::string& json, const std::string& key) {
    const hjp::FieldPos f = hjp::findField(json, key);
    if (!f.found || f.valueStart >= json.size() || json[f.valueStart] != '{')
        return "{}";
    const size_t end = hjp::skipValue(json, f.valueStart);
    if (end == std::string::npos) return "{}";
    return json.substr(f.valueStart, end - f.valueStart);
}
