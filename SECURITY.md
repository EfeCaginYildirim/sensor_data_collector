# Safe GitHub publishing

Before publishing, initialize Git and review the files that will be committed:

```bash
git init
git add .
git status
```

Do not force-add any ignored file. In particular, never publish:

- `scheduler_config.properties`
- files created in `data_files/`
- files created in `logs/`
- `last_run_timestamp.txt`

Use `scheduler_config.properties.example` as the safe configuration template. Keep provider URLs, database addresses, credentials, IDs, operational data, and logs only in ignored local files.
