#!/bin/sh
# Starts the OneGallery gateway. First run: creates .venv and installs the three packages it needs.
cd "$(dirname "$0")" || exit 1
if [ ! -d .venv ]; then
  python3 -m venv .venv && .venv/bin/python -m pip install -q -r requirements.txt || exit 1
fi
exec .venv/bin/python -m onegallery_gateway "$@"
