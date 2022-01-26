{ pkgs ? import <nixpkgs> {}}:
# let
#   br = pkgs.writeScriptBin "br" ''
#     ${pkgs.fd}/bin/fd scala | ${pkgs.entr}/bin/entr -r ${
#       bloop
#     }/bin/bloop run $(${bloop}/bin/bloop projects list | head -n 1)
#   '';
# in
let
  spkgs = rec {
    jdk = pkgs.openjdk.override{enableJavaFX = false;};
    jre = pkgs.openjdk17_headless;
    scala = pkgs.scala.override{inherit jre;};
    sbt = pkgs.sbt.override{inherit jre;};
    coursier = pkgs.coursier.override{inherit jre;};
    metals = pkgs.metals.override{inherit coursier jdk jre;};
  };
in pkgs.mkShell {
  buildInputs = with spkgs; [
    metals
    sbt
    pkgs.entr
  ];
  shellHook = ''
  '';
}
