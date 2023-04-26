{

  description = "applications for recovering snowplow bad rows";

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixpkgs-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let pkgs = import nixpkgs {
            inherit system;
            config.allowUnfree = true;
            config.allowUnsupportedSystem = true;
          };
          spkgs = rec {
            jre = pkgs.openjdk11;
            scala = pkgs.scala.override{inherit jre;};
            sbt = pkgs.sbt.override{inherit jre;};
            coursier = pkgs.coursier.override{inherit jre;};
            metals = pkgs.metals.override{inherit coursier jre;};
          };
      in {
        devShell = pkgs.mkShell {
          buildInputs = with spkgs; [
            jre
            metals
            sbt
            pkgs.entr
            pkgs.awscli
            pkgs.nodePackages.snyk
            pkgs.lzop
          ];
        };
      }
    );
}
