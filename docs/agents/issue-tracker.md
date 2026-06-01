# Issue tracker

Задачи проекта ведутся в GitHub Issues репозитория `syrok0010/NextGallery`.

Работать через GitHub CLI:

```bash
gh issue list
gh issue view <number>
gh issue create
```

Правила:

- Создавать issues как вертикальные slices, которые можно проверить отдельно.
- В issue body указывать acceptance criteria и зависимости.
- Не создавать много labels без необходимости.
- Для задач, готовых к автономной реализации агентом, использовать label `ready-for-agent`.
- Для задач, где нужно решение автора проекта, использовать label `needs-decision`.
- PR должен ссылаться на issue через `Closes #<number>` или `Refs #<number>`.
