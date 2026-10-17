# Fonctions communes aux contrôles CI. À sourcer, pas à exécuter.

# Liste les fichiers du dépôt à contrôler, un par ligne (chemins « ./… »).
# Dans un dépôt git : fichiers suivis ou non ignorés (les fichiers locaux ignorés ne partent jamais en ligne).
# Sinon : tout, sauf .git et les caches d'outils. Dans les deux cas, seul le dossier `build/` situé à la racine
# du dépôt ou d'un module (./build/, ./<module>/build/) est écarté : un package source nommé « build » reste contrôlé.
repo_files() {
  {
    if git rev-parse --is-inside-work-tree >/dev/null 2>&1 && [ "$(git rev-parse --show-prefix)" = "" ]; then
      git ls-files -z --cached --others --exclude-standard | xargs -0 -I{} printf './%s\n' {}
    else
      find . \( -path ./.git -o -path ./.gradle -o -path ./.kotlin -o -path ./.idea \) -prune -o -type f -print
    fi
  } | grep -vE '^\./([^/]+/)?build/' | while IFS= read -r f; do [ -f "$f" ] && printf '%s\n' "$f"; done
}
