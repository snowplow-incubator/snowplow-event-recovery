{
  description = "applications for recovering snowplow bad rows";

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixpkgs-unstable";
    nixpkgs-metals.url = "github:jpaju/nixpkgs/metals-1.3.0";
    flake-utils.url = "github:numtide/flake-utils";
    flake-utils.inputs.nixpkgs.follows = "nixpkgs";
    devenv.url = "github:cachix/devenv";
    devenv.inputs.nixpkgs.follows = "nixpkgs";
  };

  outputs = {
    nixpkgs,
    nixpkgs-metals,
    flake-utils,
    devenv,
    ...
  } @ inputs:
    flake-utils.lib.eachDefaultSystem (
      system: let
        pkgs = import nixpkgs {
          inherit system;
          config.allowUnfree = true;
          config.allowUnsupportedSystem = true;
        };
        metalsPkgs = import nixpkgs-metals {
          inherit system;
          config.allowUnfree = true;
          config.allowUnsupportedSystem = true;
        };
        jre = pkgs.openjdk11;
        sbt = pkgs.sbt.override {inherit jre;};
        coursier = pkgs.coursier.override {inherit jre;};
        metals = metalsPkgs.metals.override {inherit coursier jre;};
      in {
        devShell = devenv.lib.mkShell {
          inherit inputs pkgs;
          modules = [
            {
              packages = [
                jre
                metals
                sbt
                pkgs.entr
                pkgs.awscli
                pkgs.snyk
              ];
              languages.nix.enable = true;
              pre-commit.hooks = {
                alejandra.enable = true;
                deadnix.enable = true;
                gitleaks = {
                  enable = false;
                  name = "gitleaks";
                  entry = "${pkgs.gitleaks}/bin/gitleaks detect --no-git --source . -v";
                };
              };
            }
          ];
        };
      }
    );
}
