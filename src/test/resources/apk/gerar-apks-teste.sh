#!/bin/bash
# Gera os APKs mínimos usados nos testes da atualização do app (AtualizacaoAppIntegracaoTest).
# São APKs de verdade (manifesto binário + assinatura v2), só que vazios. As chaves de teste ficam
# fora do git: se não existirem, são criadas. Ao recriar a chave A, atualize
# rfid.app.certificado-sha256 em src/test/resources/application-test.yml (o script mostra a impressão).
#
# Uso (Git Bash): ANDROID_SDK=/c/Users/.../Android/Sdk JAVA_HOME=... ./gerar-apks-teste.sh
set -e
cd "$(dirname "$0")"
SDK="${ANDROID_SDK:?informe ANDROID_SDK}"
BT="$SDK/build-tools/36.0.0"
JAR="$SDK/platforms/android-36/android.jar"
KEYTOOL="$JAVA_HOME/bin/keytool"
TMP=$(mktemp -d)

chave() {  # nome
  [ -f "/tmp/rfid-teste-$1.p12" ] && return
  "$KEYTOOL" -genkeypair -keystore "/tmp/rfid-teste-$1.p12" -storetype PKCS12 -alias teste -keyalg RSA -keysize 2048 \
    -validity 10000 -dname "CN=Teste $1" -storepass teste123 -keypass teste123 >/dev/null 2>&1
}

apk() {  # arquivo codigo versao chave pacote
  cat > "$TMP/AndroidManifest.xml" <<XML
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$5">
  <application android:label="Teste" />
</manifest>
XML
  "$BT/aapt2.exe" link -o "$TMP/base.apk" -I "$JAR" --manifest "$TMP/AndroidManifest.xml" \
    --version-code "$2" --version-name "$3" --min-sdk-version 26 --target-sdk-version 36
  "$BT/zipalign.exe" -f 4 "$TMP/base.apk" "$TMP/alinhado.apk"
  cmd //c "$(cygpath -w "$BT/apksigner.bat")" sign --ks "$(cygpath -w /tmp/rfid-teste-$4.p12)" --ks-pass pass:teste123 \
    --out "$(cygpath -w "$PWD/$1")" "$(cygpath -w "$TMP/alinhado.apk")"
  rm -f "$1.idsig"
}

chave a; chave b
P=br.com.bkpbr.rfidandroid
apk rfid-teste-1.0-1.apk 1 1.0 a $P
apk rfid-teste-2.0-2.apk 2 2.0 a $P
apk rfid-teste-3.0-3.apk 3 3.0 a $P
apk rfid-teste-4.0-4.apk 4 4.0 a $P
apk rfid-outra-chave-5.apk 5 5.0 b $P
apk rfid-outro-pacote-5.apk 5 5.0 a br.com.outro.app
rm -rf "$TMP"

echo "Impressão digital SHA-256 da chave A (application-test.yml):"
"$KEYTOOL" -list -v -keystore /tmp/rfid-teste-a.p12 -storepass teste123 | grep -i "SHA256:" | head -1
