@ECHO OFF

java -Dlog4j2.configurationFile="log4j2.xml" --add-opens java.base/java.io=ALL-UNNAMED -cp p2pstorage.jar org.gbtc.storage.client.P2PClientApp %1 %2 %3 %4 %5 %6 %7 %8